package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.pipeline.ExtractionResult;
import com.devwuu.mocha.pipeline.MatchResult;
import com.devwuu.mocha.pipeline.NoteCandidate;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.render.SiteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 확인 상태 머신의 오케스트레이터 — {@link ConversationRouter}가 정한 분기의 실제 파이프라인 일을 맡는다
 * (ref: plan.md §1 [2]~[7], ADR-3). T3-2 임시 스텁(LoggingConfirmationFlow)을 대체한다.
 * <ul>
 *   <li>{@link #startNewNote} — 신규 파이프라인 배선: 후보 조회 → {@link NoteExtractor 추출} →
 *       {@link NoteMatcher 매칭} → draft 조립(source=user 마킹) → {@link NoteEnricher 보강} → slug 확정 →
 *       {@link PendingStore#put} → {@link PreviewMessenger#publish} → preview_ts 반영 재저장. (tasks T3-6)</li>
 *   <li>{@link #confirmSave} — pending 로드·TTL 판정(V-7) → {@link NoteRepository#upsertEntry}로 커밋 →
 *       pending clear → {@link SiteRenderer#renderAll} 트리거 → 완료 안내(노트 경로). (tasks T3-5)</li>
 *   <li>{@link #cancel} — pending 폐기 + 취소 안내. 저장은 일어나지 않는다(AC-4).</li>
 * </ul>
 * <p>{@link #revisePending}(pending 수정 반영)의 오케스트레이션 배선은 아직 미완이다 — 구성요소
 * ({@code PendingReviser}/{@code PreviewMessenger})는 존재하지만 이 흐름으로 엮는 일은 T3-7 몫이라 현 동작(로그)을
 * 보존한다.
 * <p>POLICY: 사용자 [저장] 확인 없이 {@link NoteRepository} 쓰기를 호출하지 않는다 (ref: plan.md#ADR-3, AC-4).
 * 신규 파이프라인은 미리보기 단계까지만 진행하며 {@link PendingStore}에만 기록한다 — 노트 JSON은 손대지 않는다.
 */
@Component
public class DefaultConfirmationFlow implements ConfirmationFlow {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfirmationFlow.class);

    // 날짜/타임스탬프는 Asia/Seoul 기준 — NoteRepository·PendingStore와 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // --- 멘트(모카 톤) 상수 — 구현 디테일, spec 결정 아님. PreviewBlocks와 같은 강아지 말투("멍" + 🐾). ---
    static final String SAVE_DONE_FMT = "저장했어요 멍! 🐾\n노트는 여기 있어요: `%s`"; // 저장했어요 멍! 🐾 …
    static final String NOTHING_TO_SAVE = "저장할 기록을 못 찾았어요 멍… 만료됐거나 이미 처리됐나 봐요 🐾"; // 만료/부재 안내(V-7)
    static final String CANCELED = "이번 기록은 지웠어요 멍! 다음에 또 마시면 불러주세요 🐾"; // 취소 안내
    static final String BROKEN_PENDING = "기록이 뭔가 이상해요 멍… 다시 보내주시겠어요? 🐾"; // 방어(엔트리/슬러그 결손)
    static final String NEW_NOTE_FAILED = "기록을 정리하다 문제가 생겼어요 멍… 잠시 뒤 다시 보내주시겠어요? 🐾"; // 추출/검색/전송 실패(plan §7)

    private final PendingStore pendingStore;
    private final NoteRepository noteRepository;
    private final SiteRenderer siteRenderer;
    private final SlackResponder responder;
    private final NoteExtractor noteExtractor;
    private final NoteMatcher noteMatcher;
    private final NoteEnricher noteEnricher;
    private final PreviewMessenger previewMessenger;
    private final String siteDir;
    private final Clock clock;

    @Autowired
    public DefaultConfirmationFlow(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            SiteRenderer siteRenderer,
            SlackResponder responder,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            PreviewMessenger previewMessenger,
            @Value("${mocha.site.dir}") String siteDir) {
        this(pendingStore, noteRepository, siteRenderer, responder,
                noteExtractor, noteMatcher, noteEnricher, previewMessenger, siteDir, Clock.system(SEOUL));
    }

    // 테스트에서 시간을 고정하기 위한 생성자(NoteRepository·PendingStore와 동일 패턴).
    DefaultConfirmationFlow(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            SiteRenderer siteRenderer,
            SlackResponder responder,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            PreviewMessenger previewMessenger,
            String siteDir,
            Clock clock) {
        this.pendingStore = pendingStore;
        this.noteRepository = noteRepository;
        this.siteRenderer = siteRenderer;
        this.responder = responder;
        this.noteExtractor = noteExtractor;
        this.noteMatcher = noteMatcher;
        this.noteEnricher = noteEnricher;
        this.previewMessenger = previewMessenger;
        this.siteDir = siteDir;
        this.clock = clock;
    }

    @Override
    public void startNewNote(IncomingMessage message) {
        String userId = message.userId();
        String channelId = message.channelId();
        try {
            LocalDate today = LocalDate.now(clock);
            List<Note> existingNotes = noteRepository.findAll();

            // [2] 추출 → [3] 매칭. LLM/스키마 실패는 여기서 예외로 던져져 아래 catch로 수렴한다(plan §7, V-1).
            ExtractionResult extraction =
                    noteExtractor.extract(message.text(), today, candidatesOf(existingNotes));
            MatchResult match = noteMatcher.match(extraction, existingNotes);

            // draft 조립(source=user 마킹) → [4] 보강(빈 필드만 source=search, V-6). 외부 호출은 여기서 끝낸다(CLAUDE.md §3).
            NoteMeta enriched = noteEnricher.enrich(userDraftMeta(extraction));

            // slug 확정: 기존=매칭 노트 slug, 신규=최초 기록일 기반 대체키(V-2, data-model §2.1).
            String slug = match.isNew()
                    ? noteRepository.nextAvailableSlug(match.targetDate().toString())
                    : match.matchedNote().slug();

            OffsetDateTime now = OffsetDateTime.now(clock);
            Entry entry = new Entry(match.targetDate(), extraction.myTaste(), extraction.rating(), List.of(), now);
            Note draft = assembleDraft(slug, extraction.coffeeName(), enriched, entry, now);
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
            log.info("신규 파이프라인 미리보기 전송: user={} slug={} match={}", userId, slug, matchInfo.type());
        } catch (Exception e) {
            // 추출·검색·전송 등 어느 단계 실패든 오류 응답으로 수렴하고 pending은 남기지 않는다(plan §7).
            log.warn("신규 파이프라인 실패(pending 미생성): user={}", userId, e);
            responder.post(channelId, NEW_NOTE_FAILED);
        }
    }

    // 기존 노트를 추출 요청의 매칭 후보(최소 식별 정보)로 축약 (ref: data-model.md#3 existing_notes).
    private static List<NoteCandidate> candidatesOf(List<Note> existingNotes) {
        return existingNotes.stream()
                .map(note -> new NoteCandidate(
                        note.slug(), note.coffeeName(),
                        note.roastery() == null ? null : note.roastery().value()))
                .toList();
    }

    // 추출 결과를 "사용자가 말한 것"만 담은 NoteMeta로 조립 — 언급한 필드만 source=user, 나머지는 null(보강 대상).
    private static NoteMeta userDraftMeta(ExtractionResult extraction) {
        return new NoteMeta(
                extraction.coffeeName(),
                userSourced(extraction.roastery()),
                userSourced(extraction.origin()),
                userSourced(extraction.process()),
                userSourced(extraction.roastLevel()),
                null,        // official_notes는 사용자 언급 없음 전제 — 보강(로스터리 출처 한정)이 채운다(FR-3)
                List.of());  // sources는 보강이 병합한다
    }

    // 보강 완료된 메타 + 이번 시음 엔트리 1건으로 확인용 draft Note를 만든다.
    private static Note assembleDraft(String slug, String coffeeName, NoteMeta meta, Entry entry, OffsetDateTime now) {
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

    @Override
    public void revisePending(IncomingMessage message, PendingNote pending) {
        // TODO(T3-4 배선): PendingReviser→PreviewMessenger로 엮는 오케스트레이션 미완.
        log.info("pending 수정 분기(오케스트레이션 미배선): user={} text={}", message.userId(), message.text());
    }

    @Override
    public void confirmSave(IncomingAction action) {
        String userId = action.userId();
        // V-7: pending 부재/TTL 초과면 저장하지 않고 만료 안내 — get()이 만료분을 빈 Optional로 준다.
        Optional<PendingNote> pendingOpt = pendingStore.get(userId);
        if (pendingOpt.isEmpty()) {
            log.info("[저장] 무효 — pending 부재/만료: user={}", userId);
            responder.post(action.channelId(), NOTHING_TO_SAVE);
            return;
        }

        Note draft = pendingOpt.get().draft();
        String slug = draft.slug();
        Entry entry = latestEntry(draft);
        // 커밋 대상은 draft.slug()(신규는 상위 파이프라인이 할당, 기존은 매칭 slug). 결손이면 저장하지 않는다.
        if (slug == null || slug.isBlank() || entry == null) {
            log.warn("[저장] 무효 — 손상된 pending(slug/entry 결손): user={} slug={}", userId, slug);
            responder.post(action.channelId(), BROKEN_PENDING);
            return;
        }

        // POLICY: 사용자 [저장] 확인을 거친 뒤에만 저장한다 (ref: plan.md#ADR-3, AC-4).
        Note saved = noteRepository.upsertEntry(slug, metaOf(draft), entry);
        pendingStore.clear(userId);
        log.info("[저장] 커밋 완료: slug={} entries={}", saved.slug(), saved.entries().size());

        // 저장은 이미 커밋됨 — 렌더 실패는 데이터 손실이 아니므로 로그만 남기고 계속한다(plan.md §7, ADR-1).
        try {
            siteRenderer.renderAll();
        } catch (RuntimeException e) {
            log.warn("리렌더 실패(노트는 저장됨, 수동 리렌더로 복구 가능): slug={}", saved.slug(), e);
        }

        responder.post(action.channelId(), String.format(SAVE_DONE_FMT, notePath(saved.slug())));
    }

    @Override
    public void cancel(IncomingAction action) {
        // [취소]는 저장 없이 pending만 폐기한다(AC-4).
        pendingStore.clear(action.userId());
        log.info("[취소] pending 폐기: user={}", action.userId());
        responder.post(action.channelId(), CANCELED);
    }

    // draft(Note)에서 노트 단위 메타만 뽑는다 — 엔트리·slug·타임스탬프는 upsertEntry가 다룬다.
    private static NoteMeta metaOf(Note draft) {
        return new NoteMeta(
                draft.coffeeName(),
                draft.roastery(),
                draft.origin(),
                draft.process(),
                draft.roastLevel(),
                draft.officialNotes(),
                draft.sources());
    }

    // 이번 시음 엔트리 — draft.entries는 1건 전제(확인 미리보기와 동일 가정). 마지막 엔트리를 취한다.
    private static Entry latestEntry(Note draft) {
        List<Entry> entries = draft.entries();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1);
    }

    // 사용자가 file://로 열 노트 상세 경로 안내 — 파생물 site/notes/<slug>.html.
    private String notePath(String slug) {
        return Path.of(siteDir, "notes", slug + ".html").toString();
    }
}
