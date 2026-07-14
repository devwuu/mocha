package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.pipeline.PendingReviser;
import com.devwuu.mocha.pipeline.SearchHit;
import com.devwuu.mocha.pipeline.SearchOutcome;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.SearchSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 수정 세션 축 전담 — 수정 세션 진입(FR-21, ADR-27)·edit 커밋·날짜 이동 충돌 판정(V-10)을
 * {@link SlackConversationFlows}(façade)에서 위임받아 소유한다(ADR-31, changes/0013).
 * façade가 조립하는 내부 협력자라 Spring 빈이 아니다 — 라우터 계약은 façade에 남는다.
 * <p>이름에 Slack을 붙인 이유: ✏️ 미리보기 전송({@link PreviewMessenger})·안내 문구 등 Slack 전송 계층에
 * 결합된 구체 flow다 — 범용 경계 인터페이스 {@link ConversationFlows}와 레벨을 구분한다
 * ({@link SlackPhotoIntake}과 동일 규칙).
 * <p>진입({@link #enterEditSession})은 {@link SlackSearchFlow}의 EDIT_TARGET_CONFIRMED 분기에서,
 * 커밋({@link #commitEdit})·충돌 재계산({@link #dateConflict})은 {@link SlackRecordFlow}의 mode=edit
 * 분기(confirmSave·revisePending)에서 호출된다 — 확인 대기(pending) 게이트는 SlackRecordFlow가 공통 소유한다.
 */
class SlackEditFlow {

    private static final Logger log = LoggerFactory.getLogger(SlackEditFlow.class);

    private final PendingStore pendingStore;
    private final SearchSessionStore searchSessionStore;
    private final NoteRepository noteRepository;
    private final NoteRenderer noteRenderer;
    private final SlackResponder responder;
    private final PreviewMessenger previewMessenger;
    private final PendingReviser pendingReviser;
    private final SlackPhotoIntake photoIntake;
    private final Clock clock;

    SlackEditFlow(
            PendingStore pendingStore,
            SearchSessionStore searchSessionStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            PreviewMessenger previewMessenger,
            PendingReviser pendingReviser,
            SlackPhotoIntake photoIntake,
            Clock clock) {
        this.pendingStore = pendingStore;
        this.searchSessionStore = searchSessionStore;
        this.noteRepository = noteRepository;
        this.noteRenderer = noteRenderer;
        this.responder = responder;
        this.previewMessenger = previewMessenger;
        this.pendingReviser = pendingReviser;
        this.photoIntake = photoIntake;
        this.clock = clock;
    }

    // 검색 세션 → 수정 세션 전환(FR-21, ADR-27, changes/0012 TΔ4) — 대상 노트+엔트리를 draft로 로드해
    // mode=edit pending을 만들고, 전환을 일으킨 텍스트(triggerText)를 진입 직후 revise로 1회 즉시 적용한 뒤
    // ✏️ 미리보기를 보낸다(changes/0016 TΔ10, ADR-39). 순수 전환 문장("그거 수정할래")이면 전 필드 null
    // 패치라 원본 그대로다(무해). 즉시 적용이 LLM 오류로 실패하면 진입은 유지하되 원본 미리보기 + 재요청 안내로
    // 수렴한다(수정 유실 금지, AC-55). 미리보기 전송 실패는 pending을 남기지 않고 호출부(SlackSearchFlow)의
    // catch(FlowMessages.SEARCH_FAILED — 검색 세션 유지)로 수렴한다.
    void enterEditSession(String userId, String channelId, SearchOutcome outcome, String triggerText, LocalDate today)
            throws Exception {
        SearchHit hit = outcome.hits().get(0);
        LocalDate targetDate = outcome.editDate();

        // POLICY: 단일 대기 원칙(FR-17/AC-30) 준용 — record든 edit든 확인 대기가 있으면 수정 세션 진입을
        //         거부하고 "먼저 저장/취소" 안내만 한다. 대기 임의 폐기는 [저장] 확답 원칙과 어긋난다
        //         (ref: plan.md#ADR-27, changes/0012 findings-TΔ0 Q2).
        if (pendingStore.get(userId).isPresent()) {
            searchSessionStore.put(userId, outcome.session()); // 단서·대상은 세션에 남긴다 — 대기 정리 후 이어서 진입 가능
            responder.post(channelId, FlowMessages.PENDING_EXISTS);
            log.info("수정 세션 진입 거부 — 확인 대기 존재(단일 대기 원칙): user={} slug={}", userId, hit.slug());
            return;
        }

        // plan §7: 수정 세션 draft 로드 실패(대상 노트/엔트리 소실) → 수정 세션 미시작 + 안내. 검색 세션은
        // 유지한다 — 사용자가 이어서 다른 기록을 찾을 수 있다.
        Optional<Note> noteOpt = noteRepository.findBySlug(hit.slug());
        Optional<Entry> target = noteOpt
                .flatMap(note -> note.entries().stream()
                        .filter(e -> targetDate.equals(e.date())).findFirst());
        if (target.isEmpty()) {
            searchSessionStore.put(userId, outcome.session());
            responder.post(channelId, FlowMessages.EDIT_TARGET_GONE);
            log.warn("수정 세션 미시작 — 대상 소실: user={} slug={} date={}", userId, hit.slug(), targetDate);
            return;
        }

        // 대상 노트+엔트리를 draft로 로드한 사본(data-model §2.3) — 원본 참조는 target{slug,date}에 남는다.
        Note note = noteOpt.orElseThrow();
        Note draft = new Note(
                note.slug(), note.coffeeName(), note.roastery(), note.origin(), note.process(),
                note.roastLevel(), note.officialNotes(), note.sources(),
                List.of(target.get()), note.createdAt(), note.updatedAt());
        OffsetDateTime now = OffsetDateTime.now(clock);
        PendingNote.EditTarget editTarget = new PendingNote.EditTarget(hit.slug(), targetDate);
        PendingNote basePending = new PendingNote(PendingNote.Mode.EDIT, draft, editTarget, null, null, now);

        // 전환 트리거 텍스트 즉시 적용(ADR-39): 진입 직후 그 텍스트를 revise로 1회 통과시켜 미리보기에 반영한다.
        // 성공하면 날짜 이동 충돌 경고(V-10)를 현 draft 기준으로 재계산해 싣고(revisePending과 동일 정신),
        // 실패하면 진입은 유지하되 원본(basePending)으로 미리보기를 보내고 아래에서 재요청 안내를 별도 전송한다.
        PendingNote toPreview = basePending;
        boolean coffeeNameRejected = false;
        boolean triggerApplyFailed = false;
        try {
            PendingReviser.ReviseOutcome revised = pendingReviser.revise(basePending, triggerText, today);
            toPreview = revised.pending().withDateConflict(dateConflict(revised.pending()));
            coffeeNameRejected = revised.coffeeNameRejected();
        } catch (Exception reviseFailure) {
            triggerApplyFailed = true;
            log.warn("전환 트리거 즉시 적용 실패 — 원본 미리보기로 진입 유지: user={} slug={}", userId, hit.slug(), reviseFailure);
        }

        // put을 먼저 해 재시작 생존(NFR-2/AC-40)을 확보하고, 전송 후 확정된 preview_ts로 재저장한다 —
        // startNewNote와 동일하게 "미리보기 없으면 pending 없음" 불변을 지킨다.
        pendingStore.put(userId, toPreview);
        try {
            String previewTs = previewMessenger.publish(channelId, toPreview);
            pendingStore.put(userId, toPreview.withPreviewTs(previewTs));
        } catch (Exception publishFailure) {
            pendingStore.clear(userId);
            throw publishFailure;
        }

        // 전환 완료 — 검색 세션의 역할(대상 찾기)은 끝났다. 남기면 수정 중 텍스트가 검색으로 샐 표면만
        // 넓어진다(전환 슬롯이 search류 전환 시 폐기되는 것과 같은 정신, ADR-26 · findings-TΔ0 Q2).
        searchSessionStore.clear(userId);
        log.info("수정 세션 진입: user={} slug={} date={} triggerApplied={} conflict={}",
                userId, hit.slug(), targetDate, !triggerApplyFailed, toPreview.dateConflict());

        // 그 턴의 1회성 안내(미리보기와 별도 텍스트): 즉시 적용 실패면 재요청(유실 금지, AC-55), 아니고 커피명
        // 변경을 시도했으면 거부 안내(V-9). 순수 전환·정상 적용이면 어느 쪽도 아니라 안내가 없다.
        if (triggerApplyFailed) {
            responder.post(channelId, FlowMessages.EDIT_TRIGGER_REVISE_FAILED);
        } else if (coffeeNameRejected) {
            responder.post(channelId, FlowMessages.EDIT_COFFEE_NAME_REJECTED);
        }
    }

    // edit 커밋(FR-21, AC-37·39, changes/0012 TΔ3) — [저장] 확답 시 대상 노트를 draft로 갱신하고 파생물을 정리한다.
    // pending 로드·TTL·결손 검증은 SlackRecordFlow의 공통 게이트(confirmSave)가 이미 끝냈다.
    void commitEdit(IncomingAction action, PendingNote pending, Entry entry) {
        String userId = action.userId();
        PendingNote.EditTarget target = pending.target();
        String slug = target.slug();

        // V-7 준용: [저장] 시점에 수정 대상(노트/엔트리)이 소실됐으면 커밋 없이 만료 안내로 수렴한다(plan §7).
        // 죽은 세션이므로 만료 경로와 동일하게 pending·스테이징 사진을 정리한다.
        Optional<Entry> origin = noteRepository.findBySlug(slug)
                .flatMap(note -> note.entries().stream()
                        .filter(e -> e.date().equals(target.date())).findFirst());
        if (origin.isEmpty()) {
            log.warn("[저장] 무효 — 수정 대상 소실: user={} slug={} date={}", userId, slug, target.date());
            pendingStore.clear(userId);
            photoIntake.discard(userId);
            responder.post(action.channelId(), FlowMessages.NOTHING_TO_SAVE);
            return;
        }

        // 수정 중 스테이징된 새 사진을 대상 엔트리 날짜의 아카이브 폴더로 이동한다(AC-Δ5 = spec AC-41).
        // 반환 경로는 노트에 싣지 않는다 — 사진은 아카이브 전용, JSON 기록 없음(changes/0014 ADR-32).
        String date = entry.date().toString();
        photoIntake.commitStaged(userId, slug, date);
        Entry committedEntry = entry;

        // POLICY: 사용자 [저장] 확인을 거친 뒤에만 저장한다 (ref: plan.md#ADR-3, AC-37).
        Note saved = noteRepository.applyEdit(slug, target.date(), withLatestEntry(pending.draft(), committedEntry));
        pendingStore.clear(userId);
        photoIntake.clearBuffer(userId);
        boolean dateMoved = !target.date().equals(entry.date());
        log.info("[저장] 수정 커밋 완료: slug={} {} → {} entries={}",
                slug, target.date(), entry.date(), saved.entries().size());

        // 파생물 정리: 날짜 이동이면 옛 date 카드부터 지운다. 삭제 실패는 커밋을 되돌리지 않고 새 카드 렌더로
        // 계속 진행한다 — 남은 옛 카드는 renderAll(--rerender)이 정리한다(plan §7, AC-39).
        if (dateMoved) {
            // POLICY: 날짜 이동 시 사진 폴더 이동은 best-effort — 실패해도 커밋을 되돌리지 않는다(사진은 옛 폴더
            //         잔류, 아카이브로서 유효). 옛 카드 삭제와 동일 정책 (ref: plan.md#ADR-32, §7, FR-21).
            try {
                photoIntake.moveEntryPhotos(slug, target.date().toString(), entry.date().toString());
            } catch (RuntimeException e) {
                log.warn("사진 폴더 이동 실패(수정은 저장됨, 사진은 옛 폴더 잔류): slug={} {} → {}",
                        slug, target.date(), entry.date(), e);
            }
            try {
                noteRenderer.removeEntryCard(slug, target.date());
            } catch (RuntimeException e) {
                log.warn("옛 카드 삭제 실패(수정은 저장됨, --rerender로 정리 가능): slug={} date={}",
                        slug, target.date(), e);
            }
        }
        // 새 date 카드 증분 렌더(+index 갱신) → 갱신 카드 배달(AC-37).
        // POLICY: 카드 이미지 생성·전송 실패는 저장을 되돌리지 않는다 — 안내 텍스트로 폴백 (ref: plan.md §7, AC-18 준용).
        try {
            Path card = noteRenderer.renderEntryCard(slug, committedEntry.date());
            responder.postImage(action.channelId(), card, FlowMessages.SAVE_DONE_CAPTION);
        } catch (RuntimeException e) {
            log.warn("카드 배달 실패(수정은 저장됨, --rerender로 복구 가능): slug={} date={}", slug, date, e);
            responder.post(action.channelId(), FlowMessages.SAVE_DONE_NO_IMAGE);
        }

        // 커밋·배달 이후 미리보기 버튼을 1회 소진한다(0009 재사용) — 실패해도 저장·배달 결과는 유지된다(ADR-20).
        finalizePreviewQuietly(action.channelId(), pending, FlowMessages.FINALIZE_SAVED);
    }

    // edit 모드 날짜 이동 충돌 판정(V-10, changes/0012 TΔ5) — 대상 노트에 이동처 date 엔트리가 이미 있으면 충돌.
    // 대상 자신의 date로는 이동이 아니므로 충돌 아님. 노트/엔트리 소실은 [저장] 시점 방어(commitEdit)가 맡는다.
    boolean dateConflict(PendingNote pending) {
        Entry entry = latestEntry(pending.draft());
        if (entry == null || pending.target() == null || entry.date().equals(pending.target().date())) {
            return false;
        }
        LocalDate movedTo = entry.date();
        return noteRepository.findBySlug(pending.target().slug())
                .map(note -> note.entries().stream().anyMatch(e -> movedTo.equals(e.date())))
                .orElse(false);
    }

    // 버튼 1회 소진 — 커밋·배달 이후 미리보기 버튼을 제거하고 상태 문구로 교체한다.
    // POLICY: 갱신 실패는 저장/취소 결과를 되돌리지 않는다 — 로그만 (ref: plan.md#ADR-20, AC-22).
    private void finalizePreviewQuietly(String channelId, PendingNote pending, String statusText) {
        try {
            responder.finalizePreview(channelId, pending, statusText);
        } catch (Exception e) {
            log.warn("미리보기 버튼 소진 실패(커밋·배달 결과 유지): channel={}", channelId, e);
        }
    }

    // draft의 이번 시음 엔트리(마지막 1건)를 교체한 새 draft를 만든다.
    private static Note withLatestEntry(Note draft, Entry entry) {
        List<Entry> entries = new ArrayList<>(draft.entries());
        entries.set(entries.size() - 1, entry);
        return new Note(
                draft.slug(), draft.coffeeName(), draft.roastery(), draft.origin(), draft.process(),
                draft.roastLevel(), draft.officialNotes(), draft.sources(),
                entries, draft.createdAt(), draft.updatedAt());
    }

    // 이번 시음 엔트리 — draft.entries는 1건 전제(확인 미리보기와 동일 가정). 마지막 엔트리를 취한다.
    private static Entry latestEntry(Note draft) {
        List<Entry> entries = draft.entries();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1);
    }
}
