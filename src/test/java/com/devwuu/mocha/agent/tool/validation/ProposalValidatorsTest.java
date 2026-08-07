package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.agent.tool.BeanArg;
import com.devwuu.mocha.agent.tool.CupArg;
import com.devwuu.mocha.agent.tool.ProposeRecordArgs;
import com.devwuu.mocha.agent.tool.RecordProposal;
import com.devwuu.mocha.agent.tool.SourcedArg;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ1(changes/0018) · TΔ2a(changes/0021): 제안 tool 서버 검증 — 검증 규칙별 통과/거부(사유 포함)를
 * 결정론으로 단언한다 (AC-Δ5, data-model §5 — beans·cups 인자는 V-14·V-15).
 * 외부 호출 없음 — 순수 도메인 검증(모듈 CLAUDE.md §5.2).
 * <p>changes/0029 TΔ1에서 검증 진입점이 {@link RecordProposalValidator} 하나로 줄었다 —
 * {@code propose_edit} 케이스군과 <b>단일 대기 케이스군(구 AC-30)</b>이 대상 구조와 함께 폐기됐다
 * (delta 0029 D-1·D-2). 확인 대기는 더 이상 검증 입력이 아니다.
 */
class ProposalValidatorsTest {

    private static final LocalDate TASTED = LocalDate.of(2026, 7, 16);
    // 연도 없는 표기 해석(V-16)의 기준 시계 — 시스템 시계 대신 고정 시계(2026-07-22)로 결정론화.
    private static final Clock FIXED = Clock.fixed(
            LocalDate.of(2026, 7, 22).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul"));
    private final RecordProposalValidator recordValidator = new RecordProposalValidator(FIXED);

    // ---- 픽스처 ----

    private static CupArg reviewCup(String myTaste, String myTasteOriginal, String rating) {
        return new CupArg(null, new CupArg.ReviewArg(myTaste, myTasteOriginal, rating));
    }

    private static ProposeRecordArgs recordArgs(String coffeeName, String rating, String targetDate,
                                                ProposeRecordArgs.MatchArg match) {
        return new ProposeRecordArgs(
                new SourcedArg<>(coffeeName, "user"),
                new SourcedArg<>("커피베라", "user"),
                List.of(new BeanArg(new SourcedArg<>("에티오피아", "search"), new SourcedArg<>(null, null))),
                new SourcedArg<>(null, null),
                new SourcedArg<>(List.of("자스민", "베르가못"), "search"),
                List.of(new CupArg(new Recipe(null, 15.0, 240.0, null, null, null, null, null, null, null),
                        new CupArg.ReviewArg("새콤하고 좋았음", "새콤하고 좋았다", rating))),
                targetDate, match,
                List.of("https://frob.co.kr/products/chelbesa"));
    }

    private static ProposeRecordArgs recordArgs() {
        return recordArgs("커피베라 예가체프 G1", "맛있다", TASTED.toString(),
                new ProposeRecordArgs.MatchArg("new", null, null));
    }

    /** 메타 최소·cups만 바꿔 끼우는 변형 — beans·cups 검증 케이스용. */
    private static ProposeRecordArgs recordArgsWith(List<BeanArg> beans, List<CupArg> cups) {
        return new ProposeRecordArgs(
                new SourcedArg<>("예가체프", "user"), null, beans, null, null, cups,
                TASTED.toString(), new ProposeRecordArgs.MatchArg("new", null, null), null);
    }

    private static String rejectionOf(ToolValidation<?> result) {
        assertThat(result).isInstanceOf(ToolValidation.Rejected.class);
        return ((ToolValidation.Rejected<?>) result).reason();
    }

    private static <T> T okOf(ToolValidation<T> result) {
        assertThat(result).isInstanceOf(ToolValidation.Ok.class);
        return ((ToolValidation.Ok<T>) result).value();
    }

    // TΔ2b 배선: 기존 검증 단언 전부를 턴 원문(단일 날짜 = 게이트 비발동)이 실린 호출로 통과시켜
    // 배선 회귀를 상시 가드한다. 다중 날짜 원문의 판정(V-16 게이트)은 MultiDateGateV16이 단언한다.
    private ToolValidation<RecordProposal> validateRecord(ProposeRecordArgs args) {
        return recordValidator.validate(args, new TurnUserMessage("7월 16일 새콤하고 좋았음", null), null);
    }

    // ---- TΔ2b 턴 원문 배선 ----

    @Nested
    class TurnUserMessageWiringT2b {

        @Test
        @DisplayName("TΔ2b: 게이트 비발동 원문(null·단일 날짜)이면 판정 결과는 동일하다 — 배선 자체는 판정에 영향 없음")
        void utteranceWiringDoesNotAffectJudgement() {
            RecordProposal withoutUtterance = okOf(recordValidator.validate(recordArgs(), null, null));
            RecordProposal withSingleDate = okOf(recordValidator.validate(recordArgs(), new TurnUserMessage("7월 16일 새콤하고 좋았음", null), null));
            assertThat(withSingleDate).isEqualTo(withoutUtterance);
        }
    }

    // ---- V-16 다중 날짜 게이트 (TΔ2c 거부 분기 · TΔ3c 세그먼트 통과 분기) ----

    @Nested
    class MultiDateGateV16 {
        // 연도 없는 표기("7월 16일")의 연도 해석 기준은 클래스 레벨 FIXED(2026-07-22) — 바깥 recordValidator를 그대로 쓴다.

        @Test
        @DisplayName("AC-Δ1: 다중 날짜 원문의 분해 우회 제안(세그먼트 부재)은 거부된다 — 사유에 탐지 집합·다음 행동 포함")
        void multiDateWithoutSegmentsRejectedWithReason() {
            String reason = rejectionOf(recordValidator.validate(recordArgs(), new TurnUserMessage("7월 16일은 새콤했고 7월 17일은 고소했음", null), null));
            assertThat(reason)
                    .contains("2026-07-16").contains("2026-07-17")   // 탐지 날짜 집합
                    .contains("V-16")                                 // 위반 이유
                    .contains("나눠 보내달라고 안내해라");              // 다음 행동 — bare rejection 금지(ADR-60)
        }

        @Test
        @DisplayName("AC-Δ1: target_date가 탐지 집합 밖이면 세그먼트가 있어도 거부된다 — 사유에 가장 이른 날짜 안내")
        void targetDateOutsideDetectedSetRejected() {
            List<TurnUserMessage.Segment> segments = List.of(
                    new TurnUserMessage.Segment(LocalDate.of(2026, 7, 16), "7월 16일은 새콤했음"),
                    new TurnUserMessage.Segment(LocalDate.of(2026, 7, 17), "7월 17일은 고소했음"));
            String reason = rejectionOf(recordValidator.validate(
                    recordArgs("커피베라 예가체프 G1", "맛있다", "2026-07-20",
                            new ProposeRecordArgs.MatchArg("new", null, null)),
                    new TurnUserMessage("7월 16일은 새콤했고 7월 17일은 고소했음", segments), null));
            assertThat(reason)
                    .contains("2026-07-20")                          // 위반 이유 — 집합 밖 target_date
                    .contains("2026-07-16").contains("2026-07-17")   // 탐지 날짜 집합
                    .contains("가장 이른 날짜(2026-07-16)");           // 다음 행동
        }

        @Test
        @DisplayName("AC-Δ2: 세그먼트 분해가 수행되고 target_date가 탐지 집합 안이면 통과한다 — V-16 완성형(TΔ3c)")
        void segmentedProposalWithinDetectedSetPasses() {
            List<TurnUserMessage.Segment> segments = List.of(
                    new TurnUserMessage.Segment(LocalDate.of(2026, 7, 16), "7월 16일은 새콤했음"),
                    new TurnUserMessage.Segment(LocalDate.of(2026, 7, 17), "7월 17일은 고소했음"));
            TurnUserMessage utterance = new TurnUserMessage("7월 16일은 새콤했고 7월 17일은 고소했음", segments);

            // 순차 제안의 첫 턴 — 가장 이른 날짜 세그먼트의 제안이 통과한다.
            RecordProposal earliest = okOf(recordValidator.validate(recordArgs(), utterance, null));
            assertThat(earliest.targetDate()).isEqualTo(LocalDate.of(2026, 7, 16));

            // 게이트 기준은 집합 소속뿐 — 이른 날짜 강제는 프롬프트 몫이라 "저장 후 이어서" 턴의
            // 나중 날짜 제안도 게이트에 막히지 않는다(ADR-60).
            RecordProposal later = okOf(recordValidator.validate(
                    recordArgs("커피베라 예가체프 G1", "맛있다", "2026-07-17",
                            new ProposeRecordArgs.MatchArg("new", null, null)), utterance, null));
            assertThat(later.targetDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        }

        @Test
        @DisplayName("V-16/ADR-60: 상대 날짜는 세지 않는다 — 절대 날짜 1개 + 상대 날짜 발화는 게이트 비발동")
        void relativeDatesDoNotTriggerGate() {
            okOf(recordValidator.validate(recordArgs(), new TurnUserMessage("어제는 별로였는데 7월 16일은 새콤하고 좋았음", null), null));
        }
    }

    // ---- V-6 draft 대조 게이트 (changes/0029 TΔ2) ----

    /**
     * 턴 입력 draft의 상위 출처 값을 하위 출처가 덮는 제안은 거부된다(V-6). 이 델타가 없애려는 실패
     * — 사용자가 폼에서 고친 값이 재제안의 검색·사진 보강으로 되돌아가는 것(delta 0029 §1.2) — 의
     * 결정론 방어선이다.
     * <p>동시에 <b>오거부를 만들지 않는지</b>도 함께 단언한다. 구 {@code SinglePendingGate}는 동일성 키에
     * 수정 대상 필드(roastery)를 섞어 "로스터리를 고치려면 로스터리가 이미 같아야 한다"는 모순을 만들었다.
     * 여기 게이트의 키는 V-9로 불변인 coffee_name뿐이라 같은 함정이 없다는 것을 {@link
     * #differentCoffeeSkipsGate()}가 못박는다.
     */
    @Nested
    class DraftPriorityGateV6 {

        private static final String COFFEE = "커피베라 예가체프 G1";

        private ToolValidation<RecordProposal> validateWithDraft(ProposeRecordArgs args, TurnDraft draft) {
            return recordValidator.validate(args, new TurnUserMessage("7월 16일 새콤하고 좋았음", null), draft);
        }

        private static ProposeRecordArgs proposal(String coffeeName, SourcedArg<String> roastery,
                                                  SourcedArg<List<String>> officialNotes, List<BeanArg> beans) {
            return new ProposeRecordArgs(
                    new SourcedArg<>(coffeeName, "user"), roastery, beans, null, officialNotes,
                    List.of(reviewCup("새콤하고 좋았음", "새콤하고 좋았다", "맛있다")),
                    TASTED.toString(), new ProposeRecordArgs.MatchArg("new", null, null), null);
        }

        private static TurnDraft draftOf(String coffeeName, Sourced<String> roastery,
                                         Sourced<List<String>> officialNotes, List<Bean> beans) {
            Note note = new Note(null, new Sourced<>(coffeeName, Source.USER), roastery, beans,
                    null, officialNotes, List.of(), List.of(), null, null);
            return new TurnDraft(note, MatchInfo.newNote());
        }

        @Test
        @DisplayName("V-6: draft의 user 값을 search 값이 덮는 제안은 거부된다 — 사유에 양쪽 값·출처·다음 행동")
        void searchOverwritingUserValueRejected() {
            TurnDraft draft = draftOf(COFFEE, new Sourced<>("모모스", Source.USER), null, List.of());

            String reason = rejectionOf(validateWithDraft(
                    proposal(COFFEE, new SourcedArg<>("FroB", "search"), null, null), draft));

            assertThat(reason)
                    .contains("roastery")
                    .contains("모모스").contains("FroB")         // 판단 근거 — 양쪽 값
                    .contains("user").contains("search")         // 판단 근거 — 양쪽 출처
                    .contains("V-6")
                    .contains("draft 값을 그대로 두고 다시 제안해라"); // 다음 행동(bare rejection 금지)
        }

        @Test
        @DisplayName("V-6: photo 값을 search가 덮어도 거부된다 — 서열은 user > photo > search 전체에 적용")
        void searchOverwritingPhotoValueRejected() {
            TurnDraft draft = draftOf(COFFEE, new Sourced<>("모모스", Source.PHOTO), null, List.of());

            assertThat(rejectionOf(validateWithDraft(
                    proposal(COFFEE, new SourcedArg<>("FroB", "search"), null, null), draft)))
                    .contains("photo").contains("search");
        }

        @Test
        @DisplayName("V-6: official_notes도 같은 대조 대상이다 — 목록 값의 하위 출처 덮어쓰기 거부")
        void officialNotesDowngradeRejected() {
            TurnDraft draft = draftOf(COFFEE, null,
                    new Sourced<>(List.of("자두", "홍차"), Source.USER), List.of());

            assertThat(rejectionOf(validateWithDraft(
                    proposal(COFFEE, null, new SourcedArg<>(List.of("청포도"), "search"), null), draft)))
                    .contains("official_notes").contains("자두").contains("청포도");
        }

        @Test
        @DisplayName("AC-1: 사용자가 직접 바꿔 말한 정정(source=user)은 통과한다 — 게이트가 수정을 막지 않는다")
        void sameRankCorrectionPasses() {
            TurnDraft draft = draftOf(COFFEE, new Sourced<>("FroB", Source.USER), null, List.of());

            RecordProposal ok = okOf(validateWithDraft(
                    proposal(COFFEE, new SourcedArg<>("모모스", "user"), null, null), draft));

            assertThat(Sourced.valueOrNull(ok.meta().roastery())).isEqualTo("모모스");
        }

        @Test
        @DisplayName("V-6: 같은 값 재보고는 출처가 낮아도 통과한다 — 되돌아간 값이 없다")
        void sameValueWithLowerSourcePasses() {
            TurnDraft draft = draftOf(COFFEE, new Sourced<>("모모스", Source.USER), null, List.of());

            okOf(validateWithDraft(proposal(COFFEE, new SourcedArg<>("모모스", "search"), null, null), draft));
        }

        @Test
        @DisplayName("V-6: 제안이 값을 비우는 것은 덮어쓰기가 아니다 — 발화로 지우는 경로를 막지 않는다")
        void clearingValuePasses() {
            TurnDraft draft = draftOf(COFFEE, new Sourced<>("모모스", Source.USER), null, List.of());

            okOf(validateWithDraft(proposal(COFFEE, new SourcedArg<>(null, null), null, null), draft));
        }

        @Test
        @DisplayName("커피명이 다르면 게이트는 발동하지 않는다 — 구 SinglePendingGate 오거부(delta §1.2)의 재발 방지")
        void differentCoffeeSkipsGate() {
            // draft에는 로스터리가 user 출처로 박혀 있지만, 이번 제안은 다른 커피다 —
            // 동일성 키에 수정 대상이 섞여 있던 구 게이트라면 여기서 정당한 제안을 거부했다.
            TurnDraft draft = draftOf(COFFEE, new Sourced<>("모모스", Source.USER), null, List.of());

            RecordProposal ok = okOf(validateWithDraft(
                    proposal("케냐 키암부 AA", new SourcedArg<>("FroB", "search"), null, null), draft));

            assertThat(Sourced.valueOrNull(ok.meta().coffeeName())).isEqualTo("케냐 키암부 AA");
            assertThat(Sourced.valueOrNull(ok.meta().roastery())).isEqualTo("FroB");
        }

        @Test
        @DisplayName("beans는 대조 대상이 아니다 — 요소 재구성(블렌드 분해·병합)이 정상 경로라 오거부를 만들지 않는다")
        void beansAreNotComparedElementWise() {
            TurnDraft draft = draftOf(COFFEE, null, null,
                    List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.USER), null)));

            okOf(validateWithDraft(proposal(COFFEE, null, null,
                    List.of(new BeanArg(new SourcedArg<>("콜롬비아 우일라", "search"), new SourcedArg<>(null, null)))),
                    draft));
        }

        @Test
        @DisplayName("draft 부재 턴은 게이트 비발동 — 첫 발화의 검색 보강이 막히지 않는다")
        void absentDraftSkipsGate() {
            okOf(validateWithDraft(proposal(COFFEE, new SourcedArg<>("FroB", "search"), null, null), null));
        }
    }

    // ---- V-1 rating ----

    @Nested
    class RatingV1 {

        @Test
        @DisplayName("V-1/AC-9: 회차 review의 rating 4범주 밖 값은 사유(허용값 안내)와 함께 거부된다")
        void invalidRatingRejectedWithReason() {
            ToolValidation<RecordProposal> result = validateRecord(
                    recordArgs("예가체프", "그냥 그래", TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("new", null, null)));
            assertThat(rejectionOf(result))
                    .contains("rating").contains("그냥 그래")
                    .contains("완전 내스타일").contains("V-1");
        }

        @Test
        @DisplayName("V-1: rating null(미언급)과 정확한 4범주 라벨은 통과한다")
        void nullAndValidRatingPass() {
            RecordProposal withNull = okOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("new", null, null))));
            assertThat(withNull.cups().getFirst().review().rating()).isNull();

            RecordProposal withLabel = okOf(validateRecord(recordArgs()));
            assertThat(withLabel.cups().getFirst().review().rating()).isEqualTo(Rating.GOOD);
        }
    }

    // ---- V-5 source enum ----

    @Nested
    class SourceV5 {

        @Test
        @DisplayName("V-5: coffee_name의 source=search는 거부된다 — 검색은 커피명을 채우지 않는다")
        void coffeeNameFromSearchRejected() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "search"), null, null, null, null, null,
                    TASTED.toString(), new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(rejectionOf(validateRecord(args)))
                    .contains("coffee_name").contains("user|photo").contains("V-5");
        }

        @Test
        @DisplayName("V-5: 정의 밖 source 값은 사유와 함께 거부된다")
        void unknownSourceRejected() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"),
                    new SourcedArg<>("커피베라", "guess"), null, null, null, null,
                    TASTED.toString(), new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(rejectionOf(validateRecord(args)))
                    .contains("roastery").contains("guess").contains("user|photo|search");
        }

        @Test
        @DisplayName("V-5: 값이 있는데 source 자기 보고가 없으면 거부된다(ADR-45)")
        void valuePresentWithoutSourceRejected() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"),
                    new SourcedArg<>("커피베라", null), null, null, null, null,
                    TASTED.toString(), new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(rejectionOf(validateRecord(args)))
                    .contains("roastery").contains("source");
        }

        @Test
        @DisplayName("V-5: 빈 값 필드는 source와 무관하게 null Sourced로 정규화된다(추측 금지)")
        void emptyValueNormalizedToNull() {
            RecordProposal proposal = okOf(validateRecord(recordArgs()));
            assertThat(proposal.meta().roastLevel()).isNull();
            // beans 요소의 빈 process도 null로 정규화된다(V-14).
            assertThat(proposal.meta().beans())
                    .containsExactly(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null));
            assertThat(proposal.meta().officialNotes().value()).containsExactly("자스민", "베르가못");
            assertThat(proposal.meta().officialNotes().source()).isEqualTo(Source.SEARCH);
        }
    }

    // ---- V-14 원두 구성(beans) — changes/0021 ADR-53 ----

    @Nested
    class BeansV14 {

        @Test
        @DisplayName("V-14/AC-64: 블렌드는 원두별 요소로 저장된다 — 원두마다 다른 process·출처 유지")
        void blendKeepsPerBeanElements() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    List.of(new BeanArg(new SourcedArg<>("에티오피아 예가체프", "user"),
                                    new SourcedArg<>("워시드", "search")),
                            new BeanArg(new SourcedArg<>("콜롬비아", "user"),
                                    new SourcedArg<>("내추럴", "user"))),
                    List.of(reviewCup("좋았음", null, null)))));
            assertThat(proposal.meta().beans()).containsExactly(
                    new Bean(new Sourced<>("에티오피아 예가체프", Source.USER), new Sourced<>("워시드", Source.SEARCH)),
                    new Bean(new Sourced<>("콜롬비아", Source.USER), new Sourced<>("내추럴", Source.USER)));
        }

        @Test
        @DisplayName("V-14: 빈 description 요소만 드롭되고 나머지 원두는 유지된다 — 저장 거부 아님")
        void emptyDescriptionElementDropped() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    List.of(new BeanArg(new SourcedArg<>("  ", "user"), new SourcedArg<>("워시드", "user")),
                            new BeanArg(new SourcedArg<>("콜롬비아", "user"), new SourcedArg<>(null, null))),
                    List.of(reviewCup("좋았음", null, null)))));
            assertThat(proposal.meta().beans())
                    .containsExactly(new Bean(new Sourced<>("콜롬비아", Source.USER), null));
        }

        @Test
        @DisplayName("V-14/V-5: beans 요소 서브필드의 source 위반은 요소 위치를 짚은 사유와 함께 거부된다")
        void beanSubfieldSourceViolationRejected() {
            assertThat(rejectionOf(validateRecord(recordArgsWith(
                    List.of(new BeanArg(new SourcedArg<>("에티오피아", "guess"), new SourcedArg<>(null, null))),
                    List.of(reviewCup("좋았음", null, null))))))
                    .contains("beans[0].description").contains("guess").contains("V-5");
        }

        @Test
        @DisplayName("V-14: beans 미언급(null)은 빈 배열로 정규화된다 — 저장 거부 아님(원두 정보는 부속)")
        void missingBeansNormalizedToEmpty() {
            RecordProposal proposal = okOf(validateRecord(
                    recordArgsWith(null, List.of(reviewCup("좋았음", null, null)))));
            assertThat(proposal.meta().beans()).isEmpty();
        }
    }

    // ---- V-8 recipe ----

    @Nested
    class RecipeV8 {

        @Test
        @DisplayName("V-8: 위반 값(음수·0·공백)은 항목만 드롭되고 저장은 거부되지 않는다")
        void invalidItemsDroppedNotRejected() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(new CupArg(new Recipe(null, -1.0, 240.0, null, null, null, "  ", null, null, null), null)))));
            // 감상 없는 recipe만의 발화 = recipe만 담긴 회차 1개(V-15 허용).
            assertThat(proposal.cups().getFirst().recipe()).isEqualTo(new Recipe(null, null, 240.0, null, null, null, null, null, null, null));
            assertThat(proposal.cups().getFirst().review()).isNull();
        }

        @Test
        @DisplayName("V-8: 확장 10필드(수치 양수·텍스트 공백)에도 항목 단위 드롭이 적용된다(changes/0021)")
        void tenFieldNormalizationAppliesPerItem() {
            Recipe raw = new Recipe("에스프레소", 18.0, null, -1.0, 28.0, 0.0, "8 (매버릭 2.0)", " ", null, "다음엔 220클릭");
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(new CupArg(raw, null)))));
            assertThat(proposal.cups().getFirst().recipe()).isEqualTo(
                    new Recipe("에스프레소", 18.0, null, null, 28.0, null, "8 (매버릭 2.0)", null, null, "다음엔 220클릭"));
        }

        @Test
        @DisplayName("V-8: recipe 전 필드 전무면 recipe 자체가 null로 정규화된다(레시피 카드 미생성 근거)")
        void allInvalidNormalizedToNull() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(new CupArg(new Recipe(null, 0.0, null, null, null, null, "", null, null, null),
                            new CupArg.ReviewArg("좋았음", null, null))))));
            // recipe 전무 정규화가 저장 거부로 번지지 않는다 — 감상 review이 있어 회차는 성립(V-15).
            assertThat(proposal.cups().getFirst().recipe()).isNull();
        }
    }

    // ---- V-15 회차(cups) — changes/0021 ADR-59 ----

    @Nested
    class CupsV15 {

        @Test
        @DisplayName("V-15: cups 미언급(null)은 회차 0개라 사유와 함께 거부된다 — 기록할 내용이 없음")
        void missingCupsRejected() {
            assertThat(rejectionOf(validateRecord(recordArgsWith(null, null))))
                    .contains("회차").contains("V-15");
        }

        @Test
        @DisplayName("V-15: 전 요소가 빈 회차(드롭)여도 회차 0개로 거부된다")
        void allEmptyCupsRejected() {
            assertThat(rejectionOf(validateRecord(recordArgsWith(
                    null, List.of(new CupArg(null, null),
                            new CupArg(new Recipe(null, 0.0, null, null, null, null, " ", null, null, null), new CupArg.ReviewArg(" ", null, null))))))).contains("회차").contains("V-15");
        }

        @Test
        @DisplayName("V-15/AC-74: 시도 2회 발화는 회차 2개로 정규화된다 — 배열 순서 = 회차 번호, 시도별 recipe·review 유지")
        void twoAttemptsBecomeTwoCups() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(null, List.of(
                    new CupArg(new Recipe(null, 15.0, 240.0, null, null, null, "210클릭 (매버릭 2.0)", null, null, null),
                            new CupArg.ReviewArg("떫었음", "떫었다", null)),
                    new CupArg(new Recipe(null, 15.0, 240.0, null, null, null, "220클릭 (매버릭 2.0)", null, null, null),
                            new CupArg.ReviewArg("부드러웠음", "부드러웠다", "맛있다"))))));
            assertThat(proposal.cups()).containsExactly(
                    new Cup(new Recipe(null, 15.0, 240.0, null, null, null, "210클릭 (매버릭 2.0)", null, null, null), new Review("떫었음", "떫었다", null)),
                    new Cup(new Recipe(null, 15.0, 240.0, null, null, null, "220클릭 (매버릭 2.0)", null, null, null),
                            new Review("부드러웠음", "부드러웠다", Rating.GOOD)));
        }

        @Test
        @DisplayName("V-15: 빈 회차 요소만 드롭되고 내용 있는 회차는 유지된다")
        void emptyCupElementDropped() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(new CupArg(null, null), reviewCup("좋았음", null, null)))));
            assertThat(proposal.cups()).containsExactly(new Cup(null, new Review("좋았음", null, null)));
        }

        @Test
        @DisplayName("V-15: 감상 발화 1건은 review을 담은 회차 1개로 정규화된다(V-11 원문 병존 포함)")
        void tasteBecomesSingleCup() {
            RecordProposal proposal = okOf(validateRecord(recordArgs()));
            assertThat(proposal.cups()).containsExactly(new Cup(
                    new Recipe(null, 15.0, 240.0, null, null, null, null, null, null, null),
                    new Review("새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD)));
        }
    }

    // ---- V-11 my_taste 병존 ----

    @Nested
    class MyTasteV11 {

        @Test
        @DisplayName("V-11: 원문 누락 시 정규화본이 양쪽에 담긴다 — 저장 거부 아님(감상 유실 방지 우선)")
        void missingOriginalFallsBackToNormalized() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(reviewCup("맛있었음", null, null)))));
            Review review = proposal.cups().getFirst().review();
            assertThat(review.myTaste()).isEqualTo("맛있었음");
            assertThat(review.myTasteOriginal()).isEqualTo("맛있었음");
        }

        @Test
        @DisplayName("V-11: 원문이 오면 정규화본과 함께 보존된다(AC-47)")
        void originalPreservedWhenPresent() {
            RecordProposal proposal = okOf(validateRecord(recordArgs()));
            Review review = proposal.cups().getFirst().review();
            assertThat(review.myTaste()).isEqualTo("새콤하고 좋았음");
            assertThat(review.myTasteOriginal()).isEqualTo("새콤하고 좋았다");
        }
    }

    // ---- 인자 형식(날짜·match·coffee_name) ----

    @Nested
    class ArgumentShape {

        @Test
        @DisplayName("coffee_name이 비어 있으면 거부된다 — 기록의 정체성 앵커(data-model §2.1)")
        void blankCoffeeNameRejected() {
            assertThat(rejectionOf(validateRecord(
                    recordArgs("  ", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("new", null, null)))))
                    .contains("coffee_name");
        }

        @Test
        @DisplayName("target_date 부재·비형식은 YYYY-MM-DD 안내와 함께 거부된다(상대 날짜는 에이전트 해석)")
        void missingOrMalformedTargetDateRejected() {
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, null, new ProposeRecordArgs.MatchArg("new", null, null))))).contains("target_date");
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, "엊그제", new ProposeRecordArgs.MatchArg("new", null, null))))).contains("YYYY-MM-DD");
        }

        @Test
        @DisplayName("match 부재·미정의 type·note_id 없는(또는 비숫자) existing은 각각 사유와 함께 거부된다(AC-15 근거)")
        void malformedMatchRejected() {
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(), null)))).contains("match");
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("maybe", null, null)))))
                    .contains("new|existing|edit");
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("existing", null, TASTED.toString())))))
                    .contains("note_id");
            // E-6(changes/0028): slug 폐기가 만든 새 실패 경로 — 비숫자 식별자는 역직렬화 예외가 아니라
            // 거부 사유로 돌아온다. 안내는 "list_notes의 숫자 id"라야 루프 안에서 정정된다(ADR-45).
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("existing", "2026-07-13-102030", TASTED.toString())))))
                    .contains("2026-07-13-102030").contains("list_notes");
        }

        @Test
        @DisplayName("match existing 통과 시 MatchInfo.existing으로 변환된다 — note_id는 Long으로 정규화")
        void existingMatchConverted() {
            RecordProposal proposal = okOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("existing", "12", "2026-07-16"))));
            assertThat(proposal.match())
                    .isEqualTo(MatchInfo.existing(12L, LocalDate.of(2026, 7, 16)));
        }

        @Test
        @DisplayName("TΔ29a: match edit 통과 시 MatchInfo.edit으로 변환된다 — existing과 같은 인자, 다른 뜻(D-14)")
        void editMatchConverted() {
            RecordProposal proposal = okOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("edit", "12", "2026-07-16"))));
            // 같은 (note_id, date)라도 existing과 같은 값이 되면 저장 경로가 갈리지 않는다 —
            // "또 마셨다"와 "그때 기록이 틀렸다"가 한 값으로 뭉개지는 자리다(D-14 ②).
            assertThat(proposal.match())
                    .isEqualTo(MatchInfo.edit(12L, LocalDate.of(2026, 7, 16)))
                    .isNotEqualTo(MatchInfo.existing(12L, LocalDate.of(2026, 7, 16)));
        }

        @Test
        @DisplayName("TΔ29a: edit은 note_id·date가 둘 다 필수 — 대상이 정해지지 않은 수정 제안은 거부된다")
        void editMatchRequiresBothTargetKeys() {
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("edit", null, "2026-07-16")))))
                    .contains("note_id");
            // date는 "고칠 엔트리"의 키다 — 추측으로 메우면 사용자가 보지 못한 회차가 갈린다(TΔ28a).
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("edit", "12", null)))))
                    .contains("date");
        }
    }
}
