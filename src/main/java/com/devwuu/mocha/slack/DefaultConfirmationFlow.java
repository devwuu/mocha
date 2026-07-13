package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.image.ImageFormat;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.domain.SearchSession;
import com.devwuu.mocha.pipeline.ExtractionResult;
import com.devwuu.mocha.pipeline.MatchResult;
import com.devwuu.mocha.pipeline.NoteCandidate;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.pipeline.NoteSearchService;
import com.devwuu.mocha.pipeline.PendingReviser;
import com.devwuu.mocha.pipeline.PhotoInfoExtractor;
import com.devwuu.mocha.pipeline.SearchHit;
import com.devwuu.mocha.pipeline.SearchOutcome;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.SearchSessionStore;
import com.devwuu.mocha.repository.StagedImage;
import com.devwuu.mocha.repository.TransitionSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 확인 상태 머신의 오케스트레이터 — {@link ConversationRouter}가 정한 분기의 실제 파이프라인 일을 맡는다
 * (ref: plan.md §1 [2]~[8], ADR-3·ADR-24). T3-2 임시 스텁(LoggingConfirmationFlow)을 대체한다.
 * <p>의도 게이트는 라우터 층({@link DefaultConversationRouter})에 있다 — 여기는 의도가 확정된 뒤의
 * 오케스트레이션과 안내 문구·전송만 소유한다(ADR-24 구현 배치, changes/0011 TΔ3).
 * <ul>
 *   <li>{@link #startNewNote} — 신규 파이프라인 배선(순수 record 경로):
 *       후보 조회 → {@link NoteExtractor 추출} →
 *       {@link NoteMatcher 매칭} → draft 조립(source=user 마킹) → {@link NoteEnricher 보강} → slug 확정 →
 *       {@link PendingStore#put} → {@link PreviewMessenger#publish} → preview_ts 반영 재저장. (tasks T3-6)</li>
 *   <li>{@link #confirmSave} — pending 로드·TTL 판정(V-7) → {@link NoteRepository#upsertEntry}로 커밋 →
 *       pending clear → {@link NoteRenderer#renderEntryCard 방금 엔트리 카드 증분 렌더} →
 *       {@link SlackResponder#postImage 카드 JPG 배달}(실패 시 텍스트 폴백, AC-18). (tasks T3-5 / changes/0002 TΔ5)
 *       mode=edit이면 {@link NoteRepository#applyEdit} 커밋 + 날짜 이동 시
 *       {@link NoteRenderer#removeEntryCard 옛 카드 정리}로 갈린다(FR-21, changes/0012 TΔ3).</li>
 *   <li>{@link #revisePending} — pending 수정 반영 배선: {@link PendingReviser#revise}로 draft에 수정 병합 →
 *       {@link PendingStore#put} → {@link PreviewMessenger#publish}로 같은 미리보기 메시지 edit(preview_ts 보존).
 *       엔트리 개수는 불변이다(AC-5). (tasks T3-7)</li>
 *   <li>{@link #cancel} — pending 폐기 + 취소 안내. 저장은 일어나지 않는다(AC-4).</li>
 *   <li>{@link #receiveMedia} — 사진 수신 버퍼 그룹핑: pending 있으면 첨부, 없으면 버퍼링. 윈도우 밖은
 *       새 흐름(FR-10, AC-8). [저장] 시 {@link PhotoStore#commit}으로 스테이징을 노트 트리로 확정한다. (tasks T4-2)</li>
 * </ul>
 * <p>POLICY: 사용자 [저장] 확인 없이 {@link NoteRepository} 쓰기를 호출하지 않는다 (ref: plan.md#ADR-3, AC-4).
 * 신규 파이프라인은 미리보기 단계까지만 진행하며 {@link PendingStore}에만 기록한다 — 노트 JSON은 손대지 않는다.
 */
@Component
public class DefaultConfirmationFlow implements ConfirmationFlow {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfirmationFlow.class);

    // 날짜/타임스탬프는 Asia/Seoul 기준 — NoteRepository·PendingStore와 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // slug 시각 세그먼트(ADR-28, V-2) — 생성 시각을 HHmmss로 붙여 날짜 세그먼트와 겹치지 않게 한다.
    private static final DateTimeFormatter SLUG_TIME = DateTimeFormatter.ofPattern("HHmmss");

    // --- 멘트(모카 톤) 상수 — 구현 디테일, spec 결정 아님. PreviewBlocks와 같은 강아지 말투("멍" + 🐾). ---
    static final String SAVE_DONE_CAPTION = "저장했어요 멍! 🐾"; // 배달하는 카드 JPG의 캡션(AC-Δ1)
    static final String SAVE_DONE_NO_IMAGE = "저장했어요 멍! 카드 이미지는 잠시 뒤에 다시 만들어 볼게요 🐾"; // 카드 렌더/전송 실패 폴백(AC-18)
    static final String NOTHING_TO_SAVE = "저장할 기록을 못 찾았어요 멍… 만료됐거나 이미 처리됐나 봐요 🐾"; // 만료/부재 안내(V-7)
    static final String CANCELED = "이번 기록은 지웠어요 멍! 다음에 또 마시면 불러주세요 🐾"; // 취소 안내
    static final String BROKEN_PENDING = "기록이 뭔가 이상해요 멍… 다시 보내주시겠어요? 🐾"; // 방어(엔트리/슬러그 결손)
    static final String NEW_NOTE_FAILED = "기록을 정리하다 문제가 생겼어요 멍… 잠시 뒤 다시 보내주시겠어요? 🐾"; // 추출/검색/전송 실패(plan §7)
    static final String REVISE_FAILED = "수정을 반영하다 문제가 생겼어요 멍… 다시 말씀해 주시겠어요? 🐾"; // 수정 병합/전송 실패(plan §7). 기존 pending은 보존
    static final String PHOTO_FAILED = "사진을 받다 문제가 생겼어요 멍… 다시 올려주시겠어요? 🐾"; // 다운로드/스테이징/전송 실패(plan §7)
    static final String UNSUPPORTED_FORMAT = "그 사진은 제가 읽을 수 없는 포맷이에요 멍… JPEG나 PNG로 다시 올려주시겠어요? 🐾"; // 매직바이트 미지원 포맷 거부 안내(ADR-29, V-12, AC-46)
    static final String NOT_A_RECORD = "저는 커피 감상을 기록하는 강아지예요 멍! 마신 커피 이야기를 들려주세요 🐾"; // 의도 게이트 other 판정 안내(AC-20)
    static final String PENDING_EXISTS = "확인을 기다리는 기록이 있어요 멍! 먼저 [저장]이나 [취소]로 마무리해 주세요 🐾"; // record+대기 존재 안내 — 단일 대기 원칙(FR-17, AC-30)
    static final String NOTHING_TO_REVISE = "지금 고칠 대기 기록이 없어요 멍… 새 커피 이야기면 그대로 들려주세요! 🐾"; // revise+대기 없음 안내(FR-17)
    // --- 검색 세션 멘트(FR-20, ADR-25, changes/0011 TΔ5) — 시작·종료 안내는 spec AC-34가 요구하는 모카 톤. ---
    static final String SEARCH_STARTED = "기록을 찾아볼게요 멍! 🐾"; // 검색 세션 시작 안내(AC-34)
    static final String SEARCH_FOUND_CAPTION = "이 기록이 맞나요 멍? 다 보셨으면 \"됐어\"라고 말해주세요 🐾"; // 단일 매치 카드 캡션(AC-31)
    static final String SEARCH_CANDIDATES_HEADER = "비슷한 기록이 여러 개예요 멍! 번호나 이름으로 골라주세요 🐾"; // 복수 후보 목록 머리말(AC-32)
    static final String SEARCH_REQUERY = "딱 맞는 기록을 못 찾았어요 멍… 날짜나 로스터리 같은 단서를 더 알려주시겠어요? 🐾"; // 무후보 재질문(AC-33)
    static final String SEARCH_LIMIT_REACHED = "이번엔 못 찾겠어요 멍… 찾기를 마칠게요. 다른 단서가 떠오르면 다시 불러주세요! 🐾"; // 재질문 상한 도달 → 세션 종료(FR-20/AC-33)
    static final String SEARCH_ENDED = "기록 찾기를 마칠게요 멍! 또 궁금하면 언제든 불러주세요 🐾"; // end 의도 종료 안내(AC-34)
    static final String SEARCH_FAILED = "기록을 찾다 문제가 생겼어요 멍… 다시 한 번 말씀해 주시겠어요? 🐾"; // 후보 선정 실패(plan §7) — 세션은 유지
    // --- 수정 세션 전환 멘트(FR-21, ADR-27, changes/0012 TΔ4) ---
    static final String EDIT_DATE_PROMPT_HEADER = "이 노트엔 기록이 여러 날 있어요 멍! 어느 날짜 기록을 고칠까요? 🐾"; // 엔트리 복수 → 날짜 목록 선택(AC-42)
    static final String EDIT_TARGET_GONE = "고치려던 기록을 못 찾았어요 멍… 사라졌나 봐요. 다시 찾아볼까요? 🐾"; // 대상 노트/엔트리 소실 → 수정 세션 미시작(plan §7)
    static final String EDIT_COFFEE_NAME_REJECTED =
            "커피 이름은 못 바꿔요 멍… 이름이 다르면 다른 커피예요! 그 커피는 새로 기록해 주세요 🐾"; // 커피명 변경 거부 + 새 등록 안내(V-9, AC-38)
    // 과거 참조 매치 실패 안내(FR-14, ADR-26, changes/0011 TΔ6) — 다음 의도(새 기록/검색)를 고르게 하고,
    // 보관이 10분뿐임을 명시해 TTL 폐기 후 일반 신규 처리로 흐르는 것이 놀랍지 않게 한다(AC-36).
    static final String REFERENCE_NOT_FOUND =
            "말씀하신 커피를 못 찾았어요 멍… \"새로 기록해줘\"라고 하면 방금 이야기 그대로 기록하고, "
                    + "\"찾아줘\"라고 하면 같이 찾아볼게요! 10분 동안 기억하고 있을게요 🐾"; // 매치 실패 → 전환 대기 안내
    // 버튼 1회 소진 상태 문구 — 미리보기 하단 버튼을 대체한다(spec AC-22 문구, changes/0009 ADR-20). 짧은 상태 배지라 강아지 톤은 절제.
    static final String FINALIZE_SAVED = "✅ 저장 완료"; // [저장] 완료 후 버튼 소진 상태 문구(AC-Δ1)
    static final String FINALIZE_CANCELED = "취소됨"; // [취소] 완료 후 버튼 소진 상태 문구(AC-Δ1)

    private final PendingStore pendingStore;
    private final NoteRepository noteRepository;
    private final NoteRenderer noteRenderer;
    private final SlackResponder responder;
    private final NoteExtractor noteExtractor;
    private final NoteMatcher noteMatcher;
    private final NoteEnricher noteEnricher;
    private final PhotoInfoExtractor photoInfoExtractor;
    private final PendingReviser pendingReviser;
    private final PreviewMessenger previewMessenger;
    private final PhotoDownloader photoDownloader;
    private final PhotoStore photoStore;
    private final PhotoBufferStore photoBufferStore;
    private final SearchSessionStore searchSessionStore;
    private final NoteSearchService noteSearchService;
    private final TransitionSlot transitionSlot;
    private final Path artifactDir;
    private final Duration bufferWindow;
    private final Clock clock;

    @Autowired
    public DefaultConfirmationFlow(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            PhotoInfoExtractor photoInfoExtractor,
            PendingReviser pendingReviser,
            PreviewMessenger previewMessenger,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            SearchSessionStore searchSessionStore,
            NoteSearchService noteSearchService,
            TransitionSlot transitionSlot,
            @Value("${mocha.artifact.dir}") String artifactDir,
            @Value("${mocha.photo.buffer-window}") Duration bufferWindow) {
        this(pendingStore, noteRepository, noteRenderer, responder,
                noteExtractor, noteMatcher, noteEnricher, photoInfoExtractor, pendingReviser, previewMessenger,
                photoDownloader, photoStore, photoBufferStore, searchSessionStore, noteSearchService, transitionSlot,
                Path.of(artifactDir), bufferWindow, Clock.system(SEOUL));
    }

    // 테스트에서 시간을 고정하기 위한 생성자(NoteRepository·PendingStore와 동일 패턴).
    DefaultConfirmationFlow(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            PhotoInfoExtractor photoInfoExtractor,
            PendingReviser pendingReviser,
            PreviewMessenger previewMessenger,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            SearchSessionStore searchSessionStore,
            NoteSearchService noteSearchService,
            TransitionSlot transitionSlot,
            Path artifactDir,
            Duration bufferWindow,
            Clock clock) {
        this.pendingStore = pendingStore;
        this.noteRepository = noteRepository;
        this.noteRenderer = noteRenderer;
        this.responder = responder;
        this.noteExtractor = noteExtractor;
        this.noteMatcher = noteMatcher;
        this.noteEnricher = noteEnricher;
        this.photoInfoExtractor = photoInfoExtractor;
        this.pendingReviser = pendingReviser;
        this.previewMessenger = previewMessenger;
        this.photoDownloader = photoDownloader;
        this.photoStore = photoStore;
        this.photoBufferStore = photoBufferStore;
        this.searchSessionStore = searchSessionStore;
        this.noteSearchService = noteSearchService;
        this.transitionSlot = transitionSlot;
        this.artifactDir = artifactDir;
        this.bufferWindow = bufferWindow;
        this.clock = clock;
    }

    @Override
    public void startNewNote(IncomingMessage message) {
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
            List<String> bufferNames = List.of();
            boolean consumeBuffer = false;
            Optional<PhotoBuffer> buffer = photoBufferStore.get(userId);
            if (buffer.isPresent()) {
                if (withinBufferWindow(buffer.get().lastMediaAt(), now)) {
                    bufferNames = buffer.get().stagedNames();
                    consumeBuffer = true;
                } else {
                    photoStore.discard(userId);
                    photoBufferStore.clear(userId);
                }
            }

            // 전환 슬롯 재개(FR-14, ADR-26): 직전 과거 참조 매치 실패의 보관분이 살아 있으면 이 텍스트("새로
            // 기록해줘")를 추출하지 않고 보관분으로 즉시 미리보기를 재개한다(감상 재전송 불요, AC-36).
            // TTL 만료·부재는 take()가 빈 Optional로 수렴 → 일반 신규 처리(추출부터).
            ExtractionResult heldExtraction = transitionSlot.take()
                    .filter(ExtractionResult.class::isInstance)
                    .map(ExtractionResult.class::cast)
                    .orElse(null);
            boolean resumed = heldExtraction != null;

            // [2] 추출 → [2.5] 수신 사진 OCR → [3] 매칭. LLM/스키마 실패는 예외로 던져져 아래 catch로 수렴한다(plan §7, V-1).
            ExtractionResult extraction = resumed
                    ? heldExtraction
                    : noteExtractor.extract(message.text(), today, candidatesOf(existingNotes));

            // [2.5] 사진이 묶여 있으면 vision OCR을 1회 시도해 빈 필드를 읽는다 — 추출 뒤·매칭 전(OCR이 읽은
            // 커피명·로스터리가 매칭 이후 draft·검색 쿼리에 기여). 실패·무정보는 첨부로만(흐름 불변, FR-19, ADR-23).
            VisionExtraction photoInfo = readPhotoInfo(userId, consumeBuffer, bufferNames, extraction);

            MatchResult match = noteMatcher.match(extraction, existingNotes);

            // POLICY: 과거 참조(references_past) 매치 실패 — pending을 만들지 않고 추출 결과를 전환 슬롯에
            //         보관한 뒤 다음 의도를 기다린다(record류=보관분 재개, search류=검색 세션+슬롯 폐기, TTL 후
            //         일반 신규 처리) (ref: spec FR-14/AC-36, plan.md#ADR-26). 재개분은 사용자가 이미 "새로
            //         기록"을 고른 것이라 이 분기에 다시 들지 않는다. 버퍼 사진은 건드리지 않는다(재개 시 흡수).
            if (!resumed && extraction.referencesPast() && match.isNew()) {
                transitionSlot.hold(extraction);
                responder.post(channelId, REFERENCE_NOT_FOUND);
                log.info("과거 참조 매치 실패 — 전환 슬롯 보관 + 안내(pending 미생성): user={} targetDate={}",
                        userId, extraction.targetDate());
                return;
            }

            // draft 조립: source=user 마킹 → OCR 오버레이(빈 필드만 source=photo, user 불가침 V-6) →
            // [4] 검색 보강(남은 빈 필드만 source=search). 외부 호출은 여기서 끝낸다(CLAUDE.md §3).
            NoteMeta withPhoto = fillFromPhoto(userDraftMeta(extraction), photoInfo);
            NoteMeta enriched = noteEnricher.enrich(withPhoto);

            // slug 확정: 기존=매칭 노트 slug, 신규=최초 기록일+생성 시각 기반 대체키(V-2, data-model §2.1).
            // POLICY: slug 형식은 YYYY-MM-DD-HHmmss(최초 기록일+생성 시각) — 사진·카드 경로 규약은 불변 (ADR-28, V-2)
            String slug = match.isNew()
                    ? noteRepository.nextAvailableSlug(
                            match.targetDate() + "-" + now.format(SLUG_TIME))
                    : match.matchedNote().slug();

            // 흡수한 버퍼 사진의 임시 미리보기 경로. 실제 저장 경로는 [저장] 시 commit이 확정한다(V-4).
            List<String> photoPaths = provisionalPhotoPaths(slug, match.targetDate(), bufferNames);
            // POLICY: 레시피는 사용자 발화 전용 — 검색·OCR 보강 금지, source 개념 없음 (ADR-22, FR-18).
            // 추출 원본을 V-8로 정규화(음수·0·공백 항목 드롭, 전무 시 recipe 자체 null)해 Entry에 싣는다.
            Recipe recipe = extraction.recipe() == null ? null
                    : Recipe.normalize(
                            extraction.recipe().doseG(), extraction.recipe().waterMl(), extraction.recipe().grind());
            Entry entry = new Entry(match.targetDate(), extraction.myTaste(), extraction.rating(), recipe, photoPaths, now);
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
                photoBufferStore.clear(userId);
            }
            log.info("신규 파이프라인 미리보기 전송: user={} slug={} match={} photos={}",
                    userId, slug, matchInfo.type(), bufferNames.size());
        } catch (Exception e) {
            // 추출·검색·전송 등 어느 단계 실패든 오류 응답으로 수렴하고 pending은 남기지 않는다(plan §7).
            log.warn("신규 파이프라인 실패(pending 미생성): user={}", userId, e);
            responder.post(channelId, NEW_NOTE_FAILED);
        }
    }

    @Override
    public void guidePendingExists(IncomingMessage message) {
        // POLICY: 단일 대기 원칙 — record 의도인데 대기가 있으면 대기를 건드리지 않고 안내만, 새 대기 미생성
        //         (ref: spec FR-17/AC-30, plan.md#ADR-24).
        log.info("record 의도 + 확인 대기 존재 — 대기 불변, 안내만: user={}", message.userId());
        responder.post(message.channelId(), PENDING_EXISTS);
    }

    @Override
    public void guideNothingToRevise(IncomingMessage message) {
        // revise 의도인데 고칠 대기가 없다 — 파이프라인 미진입, 안내만(FR-17).
        log.info("revise 의도 + 확인 대기 없음 — 안내만: user={}", message.userId());
        responder.post(message.channelId(), NOTHING_TO_REVISE);
    }

    @Override
    public void guideNotARecord(IncomingMessage message) {
        // other 판정 — 추출·보강·pending·미리보기 없이 짧은 안내로 종료한다. 버퍼도 건드리지 않는다(AC-20).
        log.info("의도 게이트 other — 파이프라인 미진입: user={}", message.userId());
        responder.post(message.channelId(), NOT_A_RECORD);
    }

    @Override
    public void searchNotes(IncomingMessage message) {
        String userId = message.userId();
        String channelId = message.channelId();

        // POLICY: search류 전환 — 전환 슬롯 보관분은 검색 세션으로 넘어가며 폐기한다
        //         (ref: spec FR-14/AC-36, plan.md#ADR-26).
        transitionSlot.take();

        // POLICY: 검색 세션은 pending을 읽기만 — 쓰기 금지(격리, AC-29) (ADR-25, FR-20).
        //         예외는 수정 전환(EDIT_TARGET_CONFIRMED, FR-21)뿐 — 그때만 edit pending을 새로 만든다(ADR-27).
        Optional<SearchSession> existing = searchSessionStore.get(userId);
        if (existing.isEmpty()) {
            // 세션 시작 안내(AC-34) — 후보 선정(LLM)보다 먼저 보내 즉시 반응한다.
            responder.post(channelId, SEARCH_STARTED);
        }
        try {
            SearchOutcome outcome = noteSearchService.handle(message.text(), existing, noteRepository.findAll());
            switch (outcome.type()) {
                case SINGLE_MATCH -> {
                    searchSessionStore.put(userId, outcome.session());
                    SearchHit hit = outcome.hits().get(0);
                    responder.postImage(channelId, cardOf(hit), SEARCH_FOUND_CAPTION);
                    // 관측(plan §6): 매치 결과 분포(단일/복수/무후보) — 오답 프록시와 함께 임베딩 전환 트리거 판단 재료.
                    log.info("검색 단일 매치 — 카드 재전송: user={} slug={} date={}", userId, hit.slug(), hit.latestDate());
                }
                case MULTIPLE_CANDIDATES -> {
                    searchSessionStore.put(userId, outcome.session());
                    responder.post(channelId, candidateListText(outcome.hits()));
                    log.info("검색 복수 후보 — 텍스트 선택 대기: user={} count={}", userId, outcome.hits().size());
                }
                case NO_MATCH -> {
                    searchSessionStore.put(userId, outcome.session());
                    responder.post(channelId, SEARCH_REQUERY);
                    log.info("검색 무후보 — 재질문: user={} requeryCount={}", userId, outcome.session().requeryCount());
                }
                case LIMIT_REACHED -> {
                    // POLICY: 재질문 상한(mocha.search-session.max-requery) 도달 → 안내 + 세션 폐기 (spec FR-20/AC-33).
                    searchSessionStore.clear(userId);
                    responder.post(channelId, SEARCH_LIMIT_REACHED);
                    log.info("검색 재질문 상한 도달 — 세션 종료: user={}", userId);
                }
                case EDIT_DATE_CHOICES -> {
                    // 수정 대상 노트 확정 + 엔트리 복수 → 날짜 목록 텍스트 선택(FR-21/AC-42). 선택 대기 상태는
                    // 세션(pendingEditSlug)이 든다 — 다음 텍스트가 같은 검색 턴에서 선택으로 해석된다.
                    searchSessionStore.put(userId, outcome.session());
                    responder.post(channelId, editDateListText(outcome.editDateChoices()));
                    log.info("수정 대상 엔트리 복수 — 날짜 선택 대기: user={} slug={} dates={}",
                            userId, outcome.hits().get(0).slug(), outcome.editDateChoices().size());
                }
                case EDIT_TARGET_CONFIRMED -> enterEditSession(userId, channelId, outcome);
            }
        } catch (Exception e) {
            // plan §7: 후보 선정 실패 → 세션을 잃지 않고 안내만(기존 세션 유지 — 다음 메시지로 계속).
            log.warn("검색 후보 선정 실패(세션 유지): user={}", userId, e);
            responder.post(channelId, SEARCH_FAILED);
        }
    }

    @Override
    public void endSearch(IncomingMessage message) {
        // end 의도 + 세션 존재는 라우터가 판정했다 — 여기는 세션 폐기와 종료 안내만(FR-17/FR-20, AC-34).
        searchSessionStore.clear(message.userId());
        log.info("검색 세션 종료(end 의도): user={}", message.userId());
        responder.post(message.channelId(), SEARCH_ENDED);
    }

    // 단일 매치 카드 경로 — POLICY: 검색 응답은 새 파생물을 만들지 않는다. 기존 카드 재사용, 파일 부재 시에만
    //                     그 엔트리 1장 증분 렌더 (ref: plan.md#ADR-25, spec FR-20/AC-31).
    private Path cardOf(SearchHit hit) {
        Path card = artifactDir.resolve("cards").resolve(hit.slug()).resolve(hit.latestDate() + ".jpg");
        if (Files.exists(card)) {
            return card;
        }
        return noteRenderer.renderEntryCard(hit.slug(), hit.latestDate());
    }

    // 검색 세션 → 수정 세션 전환(FR-21, ADR-27, changes/0012 TΔ4) — 대상 노트+엔트리를 draft로 로드해
    // mode=edit pending을 만들고 ✏️ 미리보기를 보낸다. 미리보기 전송 실패는 pending을 남기지 않고 밖의
    // catch(SEARCH_FAILED — 검색 세션 유지)로 수렴한다.
    private void enterEditSession(String userId, String channelId, SearchOutcome outcome) throws Exception {
        SearchHit hit = outcome.hits().get(0);
        LocalDate targetDate = outcome.editDate();

        // POLICY: 단일 대기 원칙(FR-17/AC-30) 준용 — record든 edit든 확인 대기가 있으면 수정 세션 진입을
        //         거부하고 "먼저 저장/취소" 안내만 한다. 대기 임의 폐기는 [저장] 확답 원칙과 어긋난다
        //         (ref: plan.md#ADR-27, changes/0012 findings-TΔ0 Q2).
        if (pendingStore.get(userId).isPresent()) {
            searchSessionStore.put(userId, outcome.session()); // 단서·대상은 세션에 남긴다 — 대기 정리 후 이어서 진입 가능
            responder.post(channelId, PENDING_EXISTS);
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
            responder.post(channelId, EDIT_TARGET_GONE);
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

        // put을 먼저 해 재시작 생존(NFR-2/AC-40)을 확보하고, 전송 후 확정된 preview_ts로 재저장한다 —
        // startNewNote와 동일하게 "미리보기 없으면 pending 없음" 불변을 지킨다.
        pendingStore.put(userId, new PendingNote(PendingNote.Mode.EDIT, draft, editTarget, null, null, now));
        try {
            String previewTs = previewMessenger.publish(
                    channelId, new PendingNote(PendingNote.Mode.EDIT, draft, editTarget, null, null, now));
            pendingStore.put(userId, new PendingNote(PendingNote.Mode.EDIT, draft, editTarget, null, previewTs, now));
        } catch (Exception publishFailure) {
            pendingStore.clear(userId);
            throw publishFailure;
        }

        // 전환 완료 — 검색 세션의 역할(대상 찾기)은 끝났다. 남기면 수정 중 텍스트가 검색으로 샐 표면만
        // 넓어진다(전환 슬롯이 search류 전환 시 폐기되는 것과 같은 정신, ADR-26 · findings-TΔ0 Q2).
        searchSessionStore.clear(userId);
        log.info("수정 세션 진입: user={} slug={} date={}", userId, hit.slug(), targetDate);
    }

    // 수정 대상 엔트리 날짜 목록(AC-42) — 번호는 "두 번째" 선택 해석 기준과 같은 순서(SearchOutcome.editDateChoices).
    private static String editDateListText(List<LocalDate> dates) {
        StringBuilder text = new StringBuilder(EDIT_DATE_PROMPT_HEADER);
        for (int i = 0; i < dates.size(); i++) {
            text.append("\n").append(i + 1).append(". ").append(dates.get(i));
        }
        return text.toString();
    }

    // 복수 후보 텍스트 목록(AC-32) — 커피명·로스터리·최근 시음일. 번호는 "두 번째" 선택 해석 기준(세션 candidateSlugs 순서).
    private static String candidateListText(List<SearchHit> hits) {
        StringBuilder text = new StringBuilder(SEARCH_CANDIDATES_HEADER);
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            text.append("\n").append(i + 1).append(". ")
                    .append(hit.coffeeName() != null ? hit.coffeeName() : hit.slug());
            if (hit.roastery() != null) {
                text.append(" — ").append(hit.roastery());
            }
            if (hit.latestDate() != null) {
                text.append(" (최근 ").append(hit.latestDate()).append(")");
            }
        }
        return text.toString();
    }

    // 기존 노트를 추출 요청의 매칭 후보(최소 식별 정보)로 축약 (ref: data-model.md#3 existing_notes).
    private static List<NoteCandidate> candidatesOf(List<Note> existingNotes) {
        return existingNotes.stream()
                .map(note -> new NoteCandidate(
                        note.slug(),
                        note.coffeeName() == null ? null : note.coffeeName().value(),
                        note.roastery() == null ? null : note.roastery().value()))
                .toList();
    }

    // [2.5] 수신 사진 OCR — 사진이 묶여 있을 때만 1콜 시도한다. 없으면 빈 결과(호출 없음).
    // hint.coffeeName은 nullable — 사진-only 흐름은 커피명을 사진에서 읽는다(ADR-23).
    private VisionExtraction readPhotoInfo(
            String userId, boolean consumeBuffer, List<String> bufferNames, ExtractionResult extraction) {
        if (!consumeBuffer || bufferNames.isEmpty()) {
            return VisionExtraction.empty();
        }
        List<StagedImage> images = photoStore.readStaged(userId);
        if (images.isEmpty()) {
            return VisionExtraction.empty();
        }
        VisionExtraction result = photoInfoExtractor.extract(
                images, new VisionHint(extraction.coffeeName(), extraction.roastery()));
        log.info("수신 사진 OCR 시도: user={} images={} coffeeName={}",
                userId, images.size(), result.coffeeName() != null);
        return result;
    }

    // OCR로 읽은 값을 사용자 draft 위에 오버레이한다 — 빈 필드만 source=photo로 채우고 사용자 값은 불가침(V-6).
    // POLICY: 우선순위 user > photo — 사진 OCR은 source=user 필드를 덮지 않는다(coffee_name 포함) (ADR-23, V-6).
    private static NoteMeta fillFromPhoto(NoteMeta user, VisionExtraction photo) {
        if (photo == null) {
            return user;
        }
        return new NoteMeta(
                fillPhoto(user.coffeeName(), photo.coffeeName()),
                fillPhoto(user.roastery(), photo.roastery()),
                fillPhoto(user.origin(), photo.origin()),
                fillPhoto(user.process(), photo.process()),
                fillPhoto(user.roastLevel(), photo.roastLevel()),
                fillPhotoList(user.officialNotes(), photo.officialNotes()),
                user.sources());
    }

    private static Sourced<String> fillPhoto(Sourced<String> current, String photoValue) {
        if (current != null && current.value() != null && !current.value().isBlank()) {
            return current; // 사용자 값 불가침(V-6)
        }
        if (photoValue == null || photoValue.isBlank()) {
            return current; // 사진에서 못 읽음 — 원래 상태(보통 null) 유지, 검색 보강 대상으로 넘김
        }
        return Sourced.photo(photoValue);
    }

    private static Sourced<List<String>> fillPhotoList(Sourced<List<String>> current, List<String> photoValue) {
        if (current != null && current.value() != null && !current.value().isEmpty()) {
            return current;
        }
        if (photoValue == null || photoValue.isEmpty()) {
            return current;
        }
        return Sourced.photo(List.copyOf(photoValue));
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

    @Override
    public void revisePending(IncomingMessage message, PendingNote pending) {
        String userId = message.userId();
        String channelId = message.channelId();
        try {
            // [1] 수정 분기: 수정 텍스트를 LLM 패치로 받아 기존 draft에 병합한다 — 엔트리 개수 불변, 새 노트 미생성(AC-5).
            // match·preview_ts·created_at은 PendingReviser가 보존하므로 같은 미리보기 메시지를 edit로 갱신하게 된다.
            PendingReviser.ReviseOutcome outcome = pendingReviser.revise(pending, message.text());
            PendingNote revised = outcome.pending();

            // POLICY: 날짜 이동 덮어쓰기는 미리보기 경고 표기 + [저장] 확답 없이는 금지 (ref: plan.md#ADR-27, V-10).
            // 충돌 여부는 revise마다 현 draft 기준으로 재계산해 pending에 영속한다 — 되돌리면 경고도 사라진다.
            if (revised.mode() == PendingNote.Mode.EDIT) {
                revised = revised.withDateConflict(hasDateConflict(revised));
            }

            // 갱신본을 먼저 영속화(재시작 생존, NFR-2) → preview_ts가 살아 있어 publish는 재전송이 아닌 edit로 갱신한다.
            pendingStore.put(userId, revised);
            previewMessenger.publish(channelId, revised);
            // 커피명 변경 거부(V-9/AC-38)는 그 턴의 1회성 안내 — 갱신된 미리보기와 별도로 텍스트로 알린다.
            if (outcome.coffeeNameRejected()) {
                responder.post(channelId, EDIT_COFFEE_NAME_REJECTED);
            }
            log.info("pending 수정 반영: user={} slug={} coffeeNameRejected={} dateConflict={}",
                    userId, revised.draft().slug(), outcome.coffeeNameRejected(), revised.dateConflict());
        } catch (Exception e) {
            // 수정 병합·전송 실패 — 신규와 달리 기존 pending은 폐기하지 않는다(이전 미리보기가 여전히 유효). 오류만 안내한다(plan §7).
            log.warn("pending 수정 실패(기존 pending 보존): user={}", userId, e);
            responder.post(channelId, REVISE_FAILED);
        }
    }

    // edit 모드 날짜 이동 충돌 판정(V-10, changes/0012 TΔ5) — 대상 노트에 이동처 date 엔트리가 이미 있으면 충돌.
    // 대상 자신의 date로는 이동이 아니므로 충돌 아님. 노트/엔트리 소실은 [저장] 시점 방어(commitEdit)가 맡는다.
    private boolean hasDateConflict(PendingNote pending) {
        Entry entry = latestEntry(pending.draft());
        if (entry == null || pending.target() == null || entry.date().equals(pending.target().date())) {
            return false;
        }
        LocalDate movedTo = entry.date();
        return noteRepository.findBySlug(pending.target().slug())
                .map(note -> note.entries().stream().anyMatch(e -> movedTo.equals(e.date())))
                .orElse(false);
    }

    @Override
    public void confirmSave(IncomingAction action) {
        String userId = action.userId();
        // V-7: pending 부재/TTL 초과면 저장하지 않고 만료 안내 — get()이 만료분을 빈 Optional로 준다.
        Optional<PendingNote> pendingOpt = pendingStore.get(userId);
        if (pendingOpt.isEmpty()) {
            log.info("[저장] 무효 — pending 부재/만료: user={}", userId);
            // 만료/부재면 대기 중이던 스테이징 사진도 버려진 것 — 노트 트리로 새지 않게 정리한다(FR-10).
            photoStore.discard(userId);
            photoBufferStore.clear(userId);
            responder.post(action.channelId(), NOTHING_TO_SAVE);
            return;
        }

        // previewTs는 pending clear 이후엔 다시 못 읽으므로 clear 전에 지역 변수로 확보한다(버튼 소진 대상, findings-TΔ0 §1).
        PendingNote pending = pendingOpt.get();
        Note draft = pending.draft();
        String slug = draft.slug();
        Entry entry = latestEntry(draft);
        boolean editMode = pending.mode() == PendingNote.Mode.EDIT;
        // 커밋 대상은 draft.slug()(신규는 상위 파이프라인이 할당, 기존은 매칭 slug). 결손이면 저장하지 않는다.
        // edit 모드는 갱신 대상 참조(target)도 필수다(data-model §2.3).
        if (slug == null || slug.isBlank() || entry == null || (editMode && pending.target() == null)) {
            log.warn("[저장] 무효 — 손상된 pending(slug/entry/target 결손): user={} slug={}", userId, slug);
            responder.post(action.channelId(), BROKEN_PENDING);
            return;
        }

        // edit 커밋은 별도 경로 — 신규 기록(record) 흐름은 mode 도입 전과 동일하게 유지한다(delta AC-Δ6).
        if (editMode) {
            commitEdit(action, pending, entry);
            return;
        }

        // 사진 커밋: 스테이징 원본을 photos/<slug>/<date>/로 이동하고 상대 경로를 확정한다(V-4, FR-10).
        // 로컬 move라 저장 커밋 경계 안에서 수행한다(외부 I/O는 수신 시점에 이미 끝남, CLAUDE.md §3).
        String date = entry.date().toString();
        List<String> committedPhotos = photoStore.commit(userId, slug, date);
        Entry committedEntry = new Entry(
                entry.date(), entry.myTaste(), entry.rating(), entry.recipe(), committedPhotos, entry.updatedAt());

        // POLICY: 사용자 [저장] 확인을 거친 뒤에만 저장한다 (ref: plan.md#ADR-3, AC-4).
        Note saved = noteRepository.upsertEntry(slug, metaOf(draft), committedEntry);
        pendingStore.clear(userId);
        photoBufferStore.clear(userId);
        log.info("[저장] 커밋 완료: slug={} entries={} photos={}",
                saved.slug(), saved.entries().size(), committedPhotos.size());

        // 저장은 이미 커밋됨 — 카드 렌더·전송 실패는 데이터 손실이 아니다. 실패해도 저장은 유지하고 안내 텍스트로 폴백한다.
        // POLICY: 저장 시점 렌더는 증분 — 방금 그 (slug,date) 엔트리 카드 1장만 굽는다(전체 재래스터화는 --rerender)
        //         (ref: plan.md#ADR-10, AC-Δ7).
        // POLICY: 카드 이미지 생성·전송 실패는 저장을 되돌리지 않는다 — 안내 텍스트로 폴백 (ref: plan.md §7, AC-18).
        try {
            Path card = noteRenderer.renderEntryCard(slug, committedEntry.date());
            responder.postImage(action.channelId(), card, SAVE_DONE_CAPTION);
        } catch (RuntimeException e) {
            log.warn("카드 배달 실패(노트는 저장됨, --rerender로 복구 가능): slug={} date={}", saved.slug(), date, e);
            responder.post(action.channelId(), SAVE_DONE_NO_IMAGE);
        }

        // 커밋·배달 이후 미리보기 버튼을 1회 소진한다 — 실패해도 저장·배달 결과는 유지된다(ADR-20, AC-Δ2).
        finalizePreviewQuietly(action.channelId(), pending, FINALIZE_SAVED);
    }

    // edit 커밋(FR-21, AC-37·39, changes/0012 TΔ3) — [저장] 확답 시 대상 노트를 draft로 갱신하고 파생물을 정리한다.
    private void commitEdit(IncomingAction action, PendingNote pending, Entry entry) {
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
            photoStore.discard(userId);
            photoBufferStore.clear(userId);
            responder.post(action.channelId(), NOTHING_TO_SAVE);
            return;
        }

        // 사진은 추가만 가능(delta 비범위: 삭제 없음) — 기존 사진은 원본 엔트리의 경로 문자열을 그대로 보존하고
        // (날짜가 이동해도 파일은 옮기지 않는다, findings-TΔ0 §3), 수정 중 스테이징된 새 사진만 뒤에 붙인다(AC-41).
        String date = entry.date().toString();
        List<String> photos = new ArrayList<>(origin.get().photos() == null ? List.of() : origin.get().photos());
        photos.addAll(photoStore.commit(userId, slug, date));
        Entry committedEntry = new Entry(
                entry.date(), entry.myTaste(), entry.rating(), entry.recipe(), photos, entry.updatedAt());

        // POLICY: 사용자 [저장] 확인을 거친 뒤에만 저장한다 (ref: plan.md#ADR-3, AC-37).
        Note saved = noteRepository.applyEdit(slug, target.date(), withLatestEntry(pending.draft(), committedEntry));
        pendingStore.clear(userId);
        photoBufferStore.clear(userId);
        boolean dateMoved = !target.date().equals(entry.date());
        log.info("[저장] 수정 커밋 완료: slug={} {} → {} entries={}",
                slug, target.date(), entry.date(), saved.entries().size());

        // 파생물 정리: 날짜 이동이면 옛 date 카드부터 지운다. 삭제 실패는 커밋을 되돌리지 않고 새 카드 렌더로
        // 계속 진행한다 — 남은 옛 카드는 renderAll(--rerender)이 정리한다(plan §7, AC-39).
        if (dateMoved) {
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
            responder.postImage(action.channelId(), card, SAVE_DONE_CAPTION);
        } catch (RuntimeException e) {
            log.warn("카드 배달 실패(수정은 저장됨, --rerender로 복구 가능): slug={} date={}", slug, date, e);
            responder.post(action.channelId(), SAVE_DONE_NO_IMAGE);
        }

        // 커밋·배달 이후 미리보기 버튼을 1회 소진한다(0009 재사용) — 실패해도 저장·배달 결과는 유지된다(ADR-20).
        finalizePreviewQuietly(action.channelId(), pending, FINALIZE_SAVED);
    }

    @Override
    public void cancel(IncomingAction action) {
        // 버튼 소진에 previewTs가 필요하므로 clear 이전에 pending을 읽어 확보한다(findings-TΔ0 §2).
        Optional<PendingNote> pendingOpt = pendingStore.get(action.userId());
        // [취소]는 저장 없이 pending만 폐기한다(AC-4). 대기 중이던 스테이징 사진·버퍼도 함께 정리한다(FR-10).
        pendingStore.clear(action.userId());
        photoStore.discard(action.userId());
        photoBufferStore.clear(action.userId());
        log.info("[취소] pending 폐기: user={}", action.userId());
        responder.post(action.channelId(), CANCELED);

        // 취소 안내 이후 미리보기 버튼을 1회 소진한다 — pending이 있었을 때만(만료/부재면 갱신 대상 없음).
        pendingOpt.ifPresent(pending -> finalizePreviewQuietly(action.channelId(), pending, FINALIZE_CANCELED));
    }

    // 버튼 1회 소진 — 커밋·배달 이후 미리보기 버튼을 제거하고 상태 문구로 교체한다.
    // POLICY: 갱신 실패는 저장/취소 결과를 되돌리지 않는다 — 로그만 (ref: plan.md#ADR-20, AC-22). responder도 내부적으로
    //         삼키지만, 여기서 한 번 더 감싸 어떤 예외도 커밋·배달 이후 흐름을 끊지 않게 한다.
    private void finalizePreviewQuietly(String channelId, PendingNote pending, String statusText) {
        try {
            responder.finalizePreview(channelId, pending, statusText);
        } catch (Exception e) {
            log.warn("미리보기 버튼 소진 실패(커밋·배달 결과 유지): channel={}", channelId, e);
        }
    }

    @Override
    public void receiveMedia(IncomingMedia media) {
        String userId = media.userId();
        String channelId = media.channelId();
        try {
            Optional<PendingNote> pending = pendingStore.get(userId);
            if (pending.isPresent()) {
                // 진행 중 노트가 있으면 사진은 그 노트에 첨부한다 — pending 중 텍스트=수정(ADR-3)과 같은 정신.
                attachToPending(userId, channelId, pending.get(), stageAll(userId, media));
            } else {
                // 담을 노트가 아직 없다 → 버퍼에 쌓아 뒤이을 텍스트를 기다린다(FR-10).
                bufferMedia(userId, media);
            }
        } catch (Exception e) {
            // 다운로드/스테이징/전송 실패는 삼키지 않고 안내로 수렴한다(plan §7).
            log.warn("사진 수신 실패: user={}", userId, e);
            responder.post(channelId, PHOTO_FAILED);
        }
    }

    // 수신 사진을 내려받아 매직바이트로 포맷을 검증한 뒤 스테이징에 저장하고 스테이징된 파일명을 순서대로 돌려준다.
    // POLICY: 스테이징에는 vision 지원 포맷(JPEG/PNG/GIF/WebP)만 — 매직바이트 검증 전 저장 금지(ADR-29, V-12).
    // 미지원 포맷은 그 사진만 버리고 안내하며(조용히 버리지 않는다), 같은 배치의 정상 사진 처리는 불변(AC-46).
    private List<String> stageAll(String userId, IncomingMedia media) {
        List<String> names = new ArrayList<>();
        boolean rejected = false;
        for (IncomingPhoto photo : media.photos()) {
            byte[] bytes = photoDownloader.download(photo.urlPrivate());
            ImageFormat format = ImageFormat.detect(bytes);
            if (!format.isVisionSupported()) {
                // 다운로드는 성공했으나 포맷이 미지원 — poison이 스테이징에 못 들어가게 입구에서 차단한다(delta #2·#3).
                log.info("미지원 포맷 사진 거부(스테이징 제외): user={} filename={} format={}",
                        userId, photo.filename(), format);
                rejected = true;
                continue;
            }
            names.add(photoStore.stage(userId, photo.filename(), bytes));
        }
        if (rejected) {
            responder.post(media.channelId(), UNSUPPORTED_FORMAT);
        }
        return names;
    }

    // 진행 중 pending의 이번 시음 엔트리에 사진을 덧붙이고 미리보기를 갱신한다(preview_ts 보존 → edit).
    private void attachToPending(String userId, String channelId, PendingNote pending, List<String> newNames)
            throws Exception {
        Note draft = pending.draft();
        Entry entry = latestEntry(draft);
        if (entry == null) {
            // 방어: 엔트리 없는 pending에는 첨부할 자리가 없다 — 버퍼로 흘리지 않고 그냥 로그만.
            log.warn("사진 첨부 무효 — 엔트리 없는 pending: user={}", userId);
            return;
        }
        List<String> merged = new ArrayList<>(entry.photos());
        merged.addAll(provisionalPhotoPaths(draft.slug(), entry.date(), newNames));
        Entry withPhotos = new Entry(entry.date(), entry.myTaste(), entry.rating(), entry.recipe(), merged, entry.updatedAt());
        PendingNote updated = pending.withDraft(withLatestEntry(draft, withPhotos));

        pendingStore.put(userId, updated);
        previewMessenger.publish(channelId, updated); // preview_ts 있음 → 같은 미리보기 edit
        log.info("pending 사진 첨부: user={} slug={} photos={}", userId, draft.slug(), merged.size());
    }

    // pending 없음: 윈도우 밖 이전 버퍼는 버리고 새로 시작, 안이면 이어붙여 버퍼링한다(AC-8).
    private void bufferMedia(String userId, IncomingMedia media) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> priorNames = List.of();
        Optional<PhotoBuffer> existing = photoBufferStore.get(userId);
        if (existing.isPresent()) {
            if (withinBufferWindow(existing.get().lastMediaAt(), now)) {
                priorNames = existing.get().stagedNames();
            } else {
                // 윈도우 밖 = 이전 버퍼는 버려진 것 → 새 흐름. 새 사진을 담기 전에 옛 스테이징을 비운다(AC-8 후반).
                photoStore.discard(userId);
                photoBufferStore.clear(userId);
            }
        }
        List<String> names = new ArrayList<>(priorNames);
        names.addAll(stageAll(userId, media));
        photoBufferStore.put(userId, new PhotoBuffer(now, names));
        log.info("사진 버퍼링: user={} photos={}", userId, names.size());
    }

    // 스테이징 파일명을 확정 저장 경로 규칙(photos/<slug>/<date>/<name>)에 맞춘 임시 미리보기 경로로 변환한다.
    // 실제 파일 이동·최종 경로는 [저장] 시 PhotoStore.commit이 정한다(충돌 시 -N 접미 가능).
    private static List<String> provisionalPhotoPaths(String slug, LocalDate date, List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        String prefix = "photos/" + slug + "/" + date + "/";
        return names.stream().map(name -> prefix + name).toList();
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

    private boolean withinBufferWindow(OffsetDateTime lastMediaAt, OffsetDateTime now) {
        return Duration.between(lastMediaAt, now).compareTo(bufferWindow) <= 0;
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
}
