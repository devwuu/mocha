package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.pipeline.ExtractionResult;
import com.devwuu.mocha.pipeline.MatchIdentity;
import com.devwuu.mocha.pipeline.MatchResult;
import com.devwuu.mocha.pipeline.NoteCandidate;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.pipeline.PendingReviser;
import com.devwuu.mocha.pipeline.PhotoHint;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.TransitionSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 기록·수정 의도 축 전담 — 신규 기록 파이프라인(추출→매칭→보강→미리보기)·pending 수정(revise)·
 * 의도 불일치 안내(FR-17)를 {@link SlackConversationFlows}(façade)에서 위임받아 소유한다
 * (ADR-31, changes/0013). façade가 조립하는 내부 협력자라 Spring 빈이 아니다 —
 * 라우터 계약은 façade에 남는다.
 * <p>[저장]/[취소] 커밋 게이트는 {@link SlackCommitHandler}로 이관됐다(changes/0018 TΔ8a) —
 * 이 flow는 미리보기(pending) 단계까지만 소유한다.
 * <p>이름에 Slack을 붙인 이유: {@link IncomingMessage}·{@link IncomingAction} 수신, Block Kit
 * 미리보기({@link PreviewMessenger}), 안내 문구 전송({@link SlackResponder}) 등 Slack 전송 계층에 결합된
 * 구체 flow다 — 범용 경계 인터페이스 {@link ConversationFlows}와 레벨을 구분한다({@link SlackPhotoIntake}과
 * 동일 규칙).
 * <p>확인 대기(pending)의 로드·TTL 판정·결손 검증은 mode와 무관하게 이 클래스가 공통 소유하고,
 * mode=edit 특화 일(커밋·날짜 충돌 재계산)만 {@link SlackEditFlow}에 위임한다.
 * <p>POLICY: 사용자 [저장] 확인 없이 {@link NoteRepository} 쓰기를 호출하지 않는다 (ref: plan.md#ADR-3, AC-4).
 * 신규 파이프라인은 미리보기 단계까지만 진행하며 {@link PendingStore}에만 기록한다 — 노트 JSON은 손대지 않는다.
 */
class SlackRecordFlow {

    private static final Logger log = LoggerFactory.getLogger(SlackRecordFlow.class);

    // slug 시각 세그먼트(ADR-28, V-2) — 생성 시각을 HHmmss로 붙여 날짜 세그먼트와 겹치지 않게 한다.
    private static final DateTimeFormatter SLUG_TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final PendingStore pendingStore;
    private final NoteRepository noteRepository;
    private final SlackResponder responder;
    private final NoteExtractor noteExtractor;
    private final NoteMatcher noteMatcher;
    private final NoteEnricher noteEnricher;
    private final PendingReviser pendingReviser;
    private final PreviewMessenger previewMessenger;
    private final TransitionSlot transitionSlot;
    private final SlackPhotoIntake photoIntake;
    private final SlackEditFlow editFlow;
    private final Clock clock;

    SlackRecordFlow(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            SlackResponder responder,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            PendingReviser pendingReviser,
            PreviewMessenger previewMessenger,
            TransitionSlot transitionSlot,
            SlackPhotoIntake photoIntake,
            SlackEditFlow editFlow,
            Clock clock) {
        this.pendingStore = pendingStore;
        this.noteRepository = noteRepository;
        this.responder = responder;
        this.noteExtractor = noteExtractor;
        this.noteMatcher = noteMatcher;
        this.noteEnricher = noteEnricher;
        this.pendingReviser = pendingReviser;
        this.previewMessenger = previewMessenger;
        this.transitionSlot = transitionSlot;
        this.photoIntake = photoIntake;
        this.editFlow = editFlow;
        this.clock = clock;
    }

    /** 신규 파이프라인 배선(순수 record 경로) — {@link ConversationFlows#startNewNote}의 실제 구현. */
    void startNewNote(IncomingMessage message) {
        String userId = message.userId();
        String channelId = message.channelId();

        // 의도 게이트는 라우터 층에서 이미 통과했다(ADR-24 구현 배치) — 여기는 순수 record 파이프라인이다.
        try {
            LocalDate today = LocalDate.now(clock);
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<Note> existingNotes = noteRepository.findAll();

            // FR-10 버퍼 그룹핑: 텍스트보다 먼저 도착해 버퍼링된 사진이 윈도우 안이면 이 노트로 흡수한다.
            // 소비(clear)는 pending 전송이 성공한 뒤에만 — 실패 시 사진이 버퍼에 남아 재시도로 살아남게 한다.
            // 윈도우 밖 버퍼는 이 텍스트에 묶이지 않는다: 버려진 스테이징을 정리하고 새 흐름으로 진행한다(AC-8 후반).
            Optional<List<String>> absorbed = photoIntake.absorbFreshBuffer(userId, now);
            boolean consumeBuffer = absorbed.isPresent();
            List<String> bufferNames = absorbed.orElse(List.of());

            // 전환 슬롯 재개(FR-14, ADR-26): 직전 과거 참조 매치 실패의 보관분이 살아 있으면 이 텍스트("새로
            // 기록해줘")를 추출하지 않고 보관분으로 즉시 미리보기를 재개한다(감상 재전송 불요, AC-36).
            // TTL 만료·부재는 take()가 빈 Optional로 수렴 → 일반 신규 처리(추출부터).
            ExtractionResult heldExtraction = transitionSlot.take()
                    .filter(ExtractionResult.class::isInstance)
                    .map(ExtractionResult.class::cast)
                    .orElse(null);
            boolean resumed = heldExtraction != null;

            // [2.0] 수신 사진 OCR → [2] 추출 → [3] 매칭. LLM/스키마 실패는 예외로 던져져 아래 catch로 수렴한다(plan §7, V-1).
            // 사진이 묶여 있으면 vision OCR을 추출보다 먼저 1회 시도해 커피명·로스터리를 읽고, 그 식별 정보를
            // 추출 photo_hint로 주입한다(ADR-36) — 텍스트에 커피명이 없어도 matched_slug 판정 재료가 된다.
            // 추출 전이라 vision 힌트는 비운다. 실패·무정보·사진 없음은 빈 결과로 수렴(흐름 불변, FR-19, ADR-23, AC-28).
            VisionExtraction photoInfo = photoIntake.readPhotoInfo(userId, bufferNames, new VisionHint(null, null));
            PhotoHint photoHint = photoInfo.coffeeName() != null || photoInfo.roastery() != null
                    ? new PhotoHint(photoInfo.coffeeName(), photoInfo.roastery())
                    : null;

            // 재개분(resumed)은 직전 턴에 이미 추출이 끝난 보관분이라 재추출·photo_hint 주입 대상이 아니다(findings-TΔ0 §2).
            ExtractionResult extraction = resumed
                    ? heldExtraction
                    : noteExtractor.extract(message.text(), today, photoHint, candidatesOf(existingNotes));

            // 매칭 식별 정보: 추출값(user) 우선, 비면 OCR(photo)이 채운 값 — V-6 오버레이와 같은 우선순위로
            // 사진-only 정체성 발화(AC-Δ3)도 별칭 대조 대상이 된다(ADR-37).
            MatchIdentity identity = new MatchIdentity(
                    extraction.coffeeName() != null ? extraction.coffeeName() : photoInfo.coffeeName(),
                    extraction.roastery() != null ? extraction.roastery() : photoInfo.roastery());
            MatchResult match = noteMatcher.match(extraction, identity, existingNotes);

            // POLICY: 과거 참조(references_past) 매치 실패 — pending을 만들지 않고 추출 결과를 전환 슬롯에
            //         보관한 뒤 다음 의도를 기다린다(record류=보관분 재개, search류=검색 세션+슬롯 폐기, TTL 후
            //         일반 신규 처리) (ref: spec FR-14/AC-36, plan.md#ADR-26). 재개분은 사용자가 이미 "새로
            //         기록"을 고른 것이라 이 분기에 다시 들지 않는다. 버퍼 사진은 건드리지 않는다(재개 시 흡수).
            if (!resumed && extraction.referencesPast() && match.isNew()) {
                transitionSlot.hold(extraction);
                responder.post(channelId, FlowMessages.REFERENCE_NOT_FOUND);
                log.info("과거 참조 매치 실패 — 전환 슬롯 보관 + 안내(pending 미생성): user={} targetDate={}",
                        userId, extraction.targetDate());
                return;
            }

            // draft 조립: source=user 마킹 → OCR 오버레이(빈 필드만 source=photo, user 불가침 V-6) →
            // [4] 검색 보강(남은 빈 필드만 source=search). 외부 호출은 여기서 끝낸다(CLAUDE.md §3).
            NoteMeta withPhoto = SlackPhotoIntake.overlayPhotoInfo(userDraftMeta(extraction), photoInfo);
            NoteMeta enriched = noteEnricher.enrich(withPhoto);

            // slug 확정: 기존=매칭 노트 slug, 신규=최초 기록일+생성 시각 기반 대체키(V-2, data-model §2.1).
            // POLICY: slug 형식은 YYYY-MM-DD-HHmmss(최초 기록일+생성 시각) — 사진·카드 경로 규약은 불변 (ADR-28, V-2)
            String slug = match.isNew()
                    ? noteRepository.nextAvailableSlug(
                            match.targetDate() + "-" + now.format(SLUG_TIME))
                    : match.matchedNote().slug();

            // POLICY: 레시피는 사용자 발화 전용 — 검색·OCR 보강 금지, source 개념 없음 (ADR-22, FR-18).
            // 추출 원본을 V-8로 정규화(음수·0·공백 항목 드롭, 전무 시 recipe 자체 null)해 Entry에 싣는다.
            // 사진은 노트에 싣지 않는다 — 아카이브 전용이라 스테이징된 채로 [저장] 시 폴더로만 커밋된다(changes/0014 ADR-32).
            Recipe recipe = extraction.recipe() == null ? null
                    : Recipe.normalize(
                            extraction.recipe().doseG(), extraction.recipe().waterMl(), extraction.recipe().grind());
            Entry entry = new Entry(match.targetDate(), extraction.myTaste(), extraction.myTasteOriginal(),
                    extraction.rating(), recipe, now);
            // coffeeName은 user 우선, 없으면 photo(OCR)로 채워진 값을 쓴다 — enriched가 그대로 실어 나른다(검색 미채움, V-5).
            Note draft = assembleDraft(slug, enriched.coffeeName(), enriched, entry, now);
            MatchInfo matchInfo = match.toMatchInfo();

            // POLICY: 노트 JSON은 건드리지 않는다 — 미리보기 단계는 pending에만 기록한다(AC-4).
            // put을 먼저 해 재시작 생존(NFR-2)을 확보하고, 전송 후 확정된 preview_ts로 재저장한다.
            pendingStore.put(userId, new PendingNote(draft, matchInfo, null, now));
            try {
                String previewTs = previewMessenger.publish(channelId, new PendingNote(draft, matchInfo, null, now));
                pendingStore.put(userId, new PendingNote(draft, matchInfo, previewTs, now));
            } catch (Exception publishFailure) {
                // 전송 실패 시 절반만 만들어진 pending을 남기지 않는다 — "미리보기 없으면 pending 없음" 불변 유지.
                pendingStore.clear(userId);
                throw publishFailure;
            }
            // 사진은 pending으로 이관됐다 — 버퍼는 비운다(스테이징 원본은 [저장] 시 commit이 옮긴다).
            if (consumeBuffer) {
                photoIntake.clearBuffer(userId);
            }
            log.info("신규 파이프라인 미리보기 전송: user={} slug={} match={} photos={}",
                    userId, slug, matchInfo.type(), bufferNames.size());
        } catch (Exception e) {
            // 추출·검색·전송 등 어느 단계 실패든 오류 응답으로 수렴하고 pending은 남기지 않는다(plan §7).
            log.warn("신규 파이프라인 실패(pending 미생성): user={}", userId, e);
            responder.post(channelId, FlowMessages.NEW_NOTE_FAILED);
        }
    }

    /** record 의도 + 확인 대기 존재 → 대기 불변, 안내만 — {@link ConversationFlows#guidePendingExists}. */
    void guidePendingExists(IncomingMessage message) {
        // POLICY: 단일 대기 원칙 — record 의도인데 대기가 있으면 대기를 건드리지 않고 안내만, 새 대기 미생성
        //         (ref: spec FR-17/AC-30, plan.md#ADR-24).
        log.info("record 의도 + 확인 대기 존재 — 대기 불변, 안내만: user={}", message.userId());
        responder.post(message.channelId(), FlowMessages.PENDING_EXISTS);
    }

    /** other 판정 → 파이프라인 미진입, 짧은 안내만(AC-20) — {@link ConversationFlows#guideNotARecord}. */
    void guideNotARecord(IncomingMessage message) {
        // other 판정 — 추출·보강·pending·미리보기 없이 짧은 안내로 종료한다. 버퍼도 건드리지 않는다(AC-20).
        log.info("의도 게이트 other — 파이프라인 미진입: user={}", message.userId());
        responder.post(message.channelId(), FlowMessages.NOT_A_RECORD);
    }

    /** pending 수정 반영 배선(FR-5, AC-5) — {@link ConversationFlows#revisePending}의 실제 구현. */
    void revisePending(IncomingMessage message, PendingNote pending) {
        String userId = message.userId();
        String channelId = message.channelId();
        try {
            // [1] 수정 분기: 수정 텍스트를 LLM 패치로 받아 기존 draft에 병합한다 — 엔트리 개수 불변, 새 노트 미생성(AC-5).
            // match·preview_ts·created_at은 PendingReviser가 보존하므로 같은 미리보기 메시지를 edit로 갱신하게 된다.
            // today를 주입해 "엊그제 마신 거였어"류 상대 날짜 정정을 해석 가능하게 한다(ADR-39, AC-56).
            LocalDate today = LocalDate.now(clock);
            PendingReviser.ReviseOutcome outcome = pendingReviser.revise(pending, message.text(), today);
            PendingNote revised = outcome.pending();

            // POLICY: 날짜 이동 덮어쓰기는 미리보기 경고 표기 + [저장] 확답 없이는 금지 (ref: plan.md#ADR-27, V-10).
            // 충돌 여부는 revise마다 현 draft 기준으로 재계산해 pending에 영속한다 — 되돌리면 경고도 사라진다.
            if (revised.mode() == PendingNote.Mode.EDIT) {
                revised = revised.withDateConflict(editFlow.dateConflict(revised));
            } else if (outcome.recordDatePatch() != null && revised.match() != null
                    && revised.match().type() == MatchInfo.MatchType.EXISTING) {
                // record 모드 시음 날짜 정정 — 매칭 표기(기존 노트 대상 날짜)를 새 날짜로 재판정해 미리보기 정합화(ADR-39, AC-56).
                // NEW 매칭은 미리보기에 날짜가 없어 갱신 대상이 아니다. 갱신/추가 구분은 MatchInfo에 없어(중립 표기) 날짜만 바꾼다.
                revised = revised.withMatch(MatchInfo.existing(revised.match().slug(), outcome.recordDatePatch()));
            }

            // 갱신본을 먼저 영속화(재시작 생존, NFR-2) → preview_ts가 살아 있어 publish는 재전송이 아닌 edit로 갱신한다.
            pendingStore.put(userId, revised);
            previewMessenger.publish(channelId, revised);
            // 커피명 변경 거부(V-9/AC-38)는 그 턴의 1회성 안내 — 갱신된 미리보기와 별도로 텍스트로 알린다.
            if (outcome.coffeeNameRejected()) {
                responder.post(channelId, FlowMessages.EDIT_COFFEE_NAME_REJECTED);
            }
            log.info("pending 수정 반영: user={} slug={} coffeeNameRejected={} dateConflict={}",
                    userId, revised.draft().slug(), outcome.coffeeNameRejected(), revised.dateConflict());
        } catch (Exception e) {
            // 수정 병합·전송 실패 — 신규와 달리 기존 pending은 폐기하지 않는다(이전 미리보기가 여전히 유효). 오류만 안내한다(plan §7).
            log.warn("pending 수정 실패(기존 pending 보존): user={}", userId, e);
            responder.post(channelId, FlowMessages.REVISE_FAILED);
        }
    }

    // 기존 노트를 추출 요청의 매칭 후보로 축약 — 식별 정보 + 별칭·원산지·official_notes·최근 시음일 확장
    // (ref: data-model.md#3 existing_notes; changes/0016 ADR-37, 동일성 판단 재료 강화).
    private static List<NoteCandidate> candidatesOf(List<Note> existingNotes) {
        return existingNotes.stream()
                .map(note -> new NoteCandidate(
                        note.slug(),
                        note.coffeeName() == null ? null : note.coffeeName().value(),
                        note.roastery() == null ? null : note.roastery().value(),
                        combinedAliases(note),
                        note.origin() == null ? null : note.origin().value(),
                        note.officialNotes() == null ? List.of() : note.officialNotes().value(),
                        lastTasted(note)))
                .toList();
    }

    // coffee_name·roastery 별칭을 하나의 통합 목록으로 — data-model §3 existing_notes.aliases(이표기 통합).
    private static List<String> combinedAliases(Note note) {
        if (note.aliases() == null) {
            return List.of();
        }
        List<String> merged = new java.util.ArrayList<>(note.aliases().coffeeName());
        merged.addAll(note.aliases().roastery());
        return Aliases.dedupNormalized(merged);
    }

    // 최근 시음일 — entries는 날짜 오름차순 유지(data-model §2.1)이므로 마지막 엔트리 날짜. 없으면 null.
    private static java.time.LocalDate lastTasted(Note note) {
        List<Entry> entries = note.entries();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1).date();
    }

    // 추출 결과를 "사용자가 말한 것"만 담은 NoteMeta로 조립 — 언급한 필드만 source=user, 나머지는 null(보강 대상).
    private static NoteMeta userDraftMeta(ExtractionResult extraction) {
        return new NoteMeta(
                userSourced(extraction.coffeeName()),
                userSourced(extraction.roastery()),
                userSourced(extraction.origin()),
                userSourced(extraction.process()),
                userSourced(extraction.roastLevel()),
                null,        // official_notes는 사용자 언급 없음 전제 — 보강(로스터리 출처 한정)이 채운다(FR-3)
                List.of());  // sources는 보강이 병합한다
    }

    // 보강 완료된 메타 + 이번 시음 엔트리 1건으로 확인용 draft Note를 만든다.
    private static Note assembleDraft(String slug, Sourced<String> coffeeName, NoteMeta meta, Entry entry, OffsetDateTime now) {
        return new Note(
                slug,
                coffeeName,
                meta.roastery(),
                meta.origin(),
                meta.process(),
                meta.roastLevel(),
                meta.officialNotes(),
                meta.sources(),
                List.of(entry),
                now,
                now);
    }

    private static Sourced<String> userSourced(String value) {
        return value == null ? null : Sourced.user(value);
    }

}
