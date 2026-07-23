package com.devwuu.mocha.agent.prompt;

import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.VisionExtraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨텍스트 조립 계약 검증 — 트랜스크립트+pending draft+OCR 결과+today가 instructions·messages로
 * 조립되는지, OCR 실패·무정보가 컨텍스트에 새지 않는지(AC-28) 결정론적으로 확인한다
 * (ref: changes/0018 tasks.md TΔ7a, plan.md#ADR-44, spec FR-22).
 */
class TurnPromptAssemblerTest {

    // Asia/Seoul 기준 2026-07-17 오전 — today 조립·상대 날짜 해석 기준(V-3)을 고정한다.
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-17T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 7, 16, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    private TurnPromptAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new TurnPromptAssembler(MochaObjectMapper.create(), FIXED);
    }

    private static Note draft() {
        Entry entry = new Entry(LocalDate.of(2026, 7, 16),
                List.of(new Brew(null, new Tasting("새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD))), NOW);
        return new Note("2026-07-16-100000",
                Sourced.user("Ethiopia Chelbesa"), Sourced.user("FroB"),
                List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, null, List.of(), List.of(entry), NOW, NOW);
    }

    @Test
    @DisplayName("ADR-44: messages = 트랜스크립트 user/mocha 쌍 재구성 + 이번 발화가 마지막 user")
    void reconstructsTranscriptIntoMessages() {
        List<TranscriptTurn> transcript = List.of(
                new TranscriptTurn("저번에 마신 예가체프 있잖아", "이 기록 말이냐멍?"),
                new TranscriptTurn("응 그거", "카드 보냈다멍!"));

        TurnPrompt context = assembler.assemble("그거 수정할래", transcript, null, null, null);

        assertThat(context.messages()).extracting(TurnPrompt.Message::role).containsExactly(
                TurnPrompt.Message.Role.USER, TurnPrompt.Message.Role.MOCHA,
                TurnPrompt.Message.Role.USER, TurnPrompt.Message.Role.MOCHA,
                TurnPrompt.Message.Role.USER);
        assertThat(context.messages()).extracting(TurnPrompt.Message::content).containsExactly(
                "저번에 마신 예가체프 있잖아", "이 기록 말이냐멍?", "응 그거", "카드 보냈다멍!", "그거 수정할래");
    }

    @Test
    @DisplayName("ADR-46: 빈 트랜스크립트(접힘 직후·첫 턴)는 이번 발화 1건만 싣는다")
    void emptyTranscriptYieldsSingleMessage() {
        TurnPrompt context = assembler.assemble("커피베라 예가체프 마셨어", List.of(), null, null, null);

        assertThat(context.messages()).hasSize(1);
        assertThat(context.messages().get(0).content()).isEqualTo("커피베라 예가체프 마셨어");
    }

    @Test
    @DisplayName("ADR-44: instructions = 시스템 프롬프트 + today(Asia/Seoul) 컨텍스트")
    void instructionsCarrySystemPromptAndToday() {
        TurnPrompt context = assembler.assemble("안녕", List.of(), null, null, null);

        assertThat(context.instructions()).startsWith(AgentSystemPrompt.INSTRUCTIONS);
        assertThat(context.instructions()).contains("today: 2026-07-17 (Asia/Seoul)");
    }

    @Test
    @DisplayName("pending 부재 시 '없음'으로 명시된다 — 대기 상태 오인 방지")
    void statesAbsenceOfPending() {
        TurnPrompt context = assembler.assemble("안녕", List.of(), null, null, null);

        assertThat(context.instructions()).contains("확인 대기(pending): 없음");
    }

    @Test
    @DisplayName("ADR-46: record pending의 mode·match·draft가 컨텍스트에 실린다 — 접힘 후 문맥의 압축본")
    void includesRecordPendingDraft() {
        PendingNote pending = new PendingNote(draft(), MatchInfo.newNote(), "171.001", NOW);

        TurnPrompt context = assembler.assemble("산미는 낮음으로 바꿔줘", List.of(), pending, null, null);

        assertThat(context.instructions()).contains("\"mode\":\"record\"");
        assertThat(context.instructions()).contains("\"type\":\"new\"");
        assertThat(context.instructions()).contains("Ethiopia Chelbesa");
        assertThat(context.instructions()).contains("새콤하고 좋았음");
        // 배관 필드는 판단 재료가 아니다 — preview_ts가 새지 않는다.
        assertThat(context.instructions()).doesNotContain("171.001");
    }

    @Test
    @DisplayName("V-10: edit pending은 target과 date_conflict까지 싣는다 — 수정 대상·경고 문맥 유지")
    void includesEditPendingTargetAndConflict() {
        PendingNote pending = new PendingNote(PendingNote.Mode.EDIT, draft(),
                new PendingNote.EditTarget("2026-07-16-100000", LocalDate.of(2026, 7, 16)),
                null, null, NOW).withDateConflict(true);

        TurnPrompt context = assembler.assemble("역시 원래 날짜로 둘래", List.of(), pending, null, null);

        assertThat(context.instructions()).contains("\"mode\":\"edit\"");
        assertThat(context.instructions()).contains("\"target\":{\"slug\":\"2026-07-16-100000\",\"date\":\"2026-07-16\"}");
        assertThat(context.instructions()).contains("\"date_conflict\":true");
    }

    @Test
    @DisplayName("FR-19: OCR 결과가 source=photo 재료로 컨텍스트에 실린다")
    void includesOcrExtraction() {
        VisionExtraction ocr = new VisionExtraction("Waikiki", "모모스 커피",
                List.of(new VisionExtraction.Bean("과테말라", "워시드"),
                        new VisionExtraction.Bean("에티오피아", null)),
                null, List.of("청포도"));

        TurnPrompt context = assembler.assemble("이거 마셨는데 좋았어", List.of(), null, ocr, null);

        assertThat(context.instructions()).contains("수신 사진 OCR 결과");
        assertThat(context.instructions()).contains("source=photo");
        assertThat(context.instructions()).contains("\"coffee_name\":\"Waikiki\"");
        assertThat(context.instructions()).contains("\"roastery\":\"모모스 커피\"");
        // beans는 원두별 요소로 직렬화돼 실린다(ADR-53) — 블렌드 원두 2종이 모두 보인다.
        assertThat(context.instructions()).contains("\"beans\"");
        assertThat(context.instructions()).contains("과테말라").contains("에티오피아");
        assertThat(context.instructions()).contains("청포도");
    }

    @Test
    @DisplayName("ADR-61/TΔ3b: 다중 날짜 세그먼트가 active_date(가장 이른 날짜)와 함께 컨텍스트에 실린다")
    void includesSegmentsWithEarliestActiveDate() {
        // 입력 순서를 일부러 뒤집는다 — active_date가 목록 순서가 아니라 최솟값 계산임을 단언한다.
        List<TurnUserMessage.Segment> segments = List.of(
                new TurnUserMessage.Segment(LocalDate.of(2026, 7, 16), "16일엔 케냐가 진했음"),
                new TurnUserMessage.Segment(LocalDate.of(2026, 7, 15), "7월 15일 에티오피아 새콤했음"));

        TurnPrompt context = assembler.assemble("이틀치 기록해줘", List.of(), null, null, segments);

        assertThat(context.instructions()).contains("다중 날짜 자동 분해 세그먼트");
        assertThat(context.instructions()).contains("\"active_date\":\"2026-07-15\"");
        // 세그먼트는 날짜·원문 발췌 그대로 직렬화된다(ADR-61 원문 보존 분할).
        assertThat(context.instructions()).contains("\"date\":\"2026-07-15\"").contains("7월 15일 에티오피아 새콤했음");
        assertThat(context.instructions()).contains("\"date\":\"2026-07-16\"").contains("16일엔 케냐가 진했음");
    }

    @Test
    @DisplayName("ADR-61: 분해 미수행·세그먼터 실패 턴(segments=null)은 세그먼트 항목을 싣지 않는다 — 흐름 불변")
    void omitsAbsentSegments() {
        TurnPrompt context = assembler.assemble("이거 마셨어", List.of(), null, null, null);

        // 주입 블록의 bullet 마커로 부재를 단언한다 — 시스템 프롬프트 정책 문구에도 "세그먼트"가
        // 등장하므로(TΔ3d 순차 제안 개정) 낱말 단위 단언은 못 쓴다.
        assertThat(context.instructions()).doesNotContain("- 다중 날짜 자동 분해 세그먼트(");
    }

    @Test
    @DisplayName("AC-28: OCR 부재·실패(empty)는 컨텍스트에 싣지 않는다 — 흐름 불변")
    void omitsAbsentOrEmptyOcr() {
        TurnPrompt without = assembler.assemble("이거 마셨어", List.of(), null, null, null);
        TurnPrompt empty = assembler.assemble("이거 마셨어", List.of(), null, VisionExtraction.empty(), null);

        assertThat(without.instructions()).doesNotContain("수신 사진 OCR 결과");
        assertThat(empty.instructions()).doesNotContain("수신 사진 OCR 결과");
        assertThat(empty.instructions()).isEqualTo(without.instructions());
    }
}
