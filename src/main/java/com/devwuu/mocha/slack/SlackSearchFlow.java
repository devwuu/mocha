package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.SearchSession;
import com.devwuu.mocha.pipeline.NoteSearchService;
import com.devwuu.mocha.pipeline.SearchHit;
import com.devwuu.mocha.pipeline.SearchOutcome;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.SearchSessionStore;
import com.devwuu.mocha.repository.TransitionSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 검색 세션 축 전담 — 검색 세션 시작·계속·종료(FR-20, ADR-25)와 후보 목록·카드 조회를
 * {@link SlackConversationFlows}(façade)에서 위임받아 소유한다(ADR-31, changes/0013).
 * façade가 조립하는 내부 협력자라 Spring 빈이 아니다 — 라우터 계약은 façade에 남는다.
 * <p>이름에 Slack을 붙인 이유: 카드 이미지 재전송·후보 목록 텍스트 등 Slack 전송 계층에 결합된 구체 flow다 —
 * 범용 경계 인터페이스 {@link ConversationFlows}와 레벨을 구분한다({@link SlackPhotoIntake}과 동일 규칙).
 * <p>수정 의도 감지(EDIT_TARGET_CONFIRMED, FR-21)의 실제 세션 전환은 {@link SlackEditFlow#enterEditSession}에
 * 위임한다 — 전환 실패는 이 클래스의 catch(검색 세션 유지)로 수렴한다.
 */
class SlackSearchFlow {

    private static final Logger log = LoggerFactory.getLogger(SlackSearchFlow.class);

    private final SearchSessionStore searchSessionStore;
    private final NoteSearchService noteSearchService;
    private final NoteRepository noteRepository;
    private final NoteRenderer noteRenderer;
    private final SlackResponder responder;
    private final TransitionSlot transitionSlot;
    private final SlackEditFlow editFlow;
    private final Path artifactDir;

    SlackSearchFlow(
            SearchSessionStore searchSessionStore,
            NoteSearchService noteSearchService,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            TransitionSlot transitionSlot,
            SlackEditFlow editFlow,
            Path artifactDir) {
        this.searchSessionStore = searchSessionStore;
        this.noteSearchService = noteSearchService;
        this.noteRepository = noteRepository;
        this.noteRenderer = noteRenderer;
        this.responder = responder;
        this.transitionSlot = transitionSlot;
        this.editFlow = editFlow;
        this.artifactDir = artifactDir;
    }

    /** 검색 세션 시작/계속(FR-20, ADR-25) — {@link ConversationFlows#searchNotes}의 실제 구현. */
    void searchNotes(IncomingMessage message) {
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
            responder.post(channelId, FlowMessages.SEARCH_STARTED);
        }
        try {
            SearchOutcome outcome = noteSearchService.handle(message.text(), existing, noteRepository.findAll());
            switch (outcome.type()) {
                case SINGLE_MATCH -> {
                    searchSessionStore.put(userId, outcome.session());
                    SearchHit hit = outcome.hits().get(0);
                    responder.postImage(channelId, cardOf(hit), FlowMessages.SEARCH_FOUND_CAPTION);
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
                    responder.post(channelId, FlowMessages.SEARCH_REQUERY);
                    log.info("검색 무후보 — 재질문: user={} requeryCount={}", userId, outcome.session().requeryCount());
                }
                case LIMIT_REACHED -> {
                    // POLICY: 재질문 상한(mocha.search-session.max-requery) 도달 → 안내 + 세션 폐기 (spec FR-20/AC-33).
                    searchSessionStore.clear(userId);
                    responder.post(channelId, FlowMessages.SEARCH_LIMIT_REACHED);
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
                case EDIT_TARGET_CONFIRMED -> editFlow.enterEditSession(userId, channelId, outcome);
            }
        } catch (Exception e) {
            // plan §7: 후보 선정 실패 → 세션을 잃지 않고 안내만(기존 세션 유지 — 다음 메시지로 계속).
            log.warn("검색 후보 선정 실패(세션 유지): user={}", userId, e);
            responder.post(channelId, FlowMessages.SEARCH_FAILED);
        }
    }

    /** 검색 세션 폐기 + 종료 안내(FR-17/FR-20, AC-34) — {@link ConversationFlows#endSearch}의 실제 구현. */
    void endSearch(IncomingMessage message) {
        // end 의도 + 세션 존재는 라우터가 판정했다 — 여기는 세션 폐기와 종료 안내만.
        searchSessionStore.clear(message.userId());
        log.info("검색 세션 종료(end 의도): user={}", message.userId());
        responder.post(message.channelId(), FlowMessages.SEARCH_ENDED);
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

    // 수정 대상 엔트리 날짜 목록(AC-42) — 번호는 "두 번째" 선택 해석 기준과 같은 순서(SearchOutcome.editDateChoices).
    private static String editDateListText(List<LocalDate> dates) {
        StringBuilder text = new StringBuilder(FlowMessages.EDIT_DATE_PROMPT_HEADER);
        for (int i = 0; i < dates.size(); i++) {
            text.append("\n").append(i + 1).append(". ").append(dates.get(i));
        }
        return text.toString();
    }

    // 복수 후보 텍스트 목록(AC-32) — 커피명·로스터리·최근 시음일. 번호는 "두 번째" 선택 해석 기준(세션 candidateSlugs 순서).
    private static String candidateListText(List<SearchHit> hits) {
        StringBuilder text = new StringBuilder(FlowMessages.SEARCH_CANDIDATES_HEADER);
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
}
