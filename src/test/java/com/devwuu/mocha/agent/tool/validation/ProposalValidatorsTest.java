package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.agent.tool.BeanArg;
import com.devwuu.mocha.agent.tool.BrewArg;
import com.devwuu.mocha.agent.tool.EditProposal;
import com.devwuu.mocha.agent.tool.ProposeEditArgs;
import com.devwuu.mocha.agent.tool.ProposeRecordArgs;
import com.devwuu.mocha.agent.tool.RecordProposal;
import com.devwuu.mocha.agent.tool.SourcedArg;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ1(changes/0018) · TΔ2a(changes/0021): 제안 tool 서버 검증 — 검증 규칙별 통과/거부(사유 포함)를
 * 결정론으로 단언한다 (AC-Δ5, data-model §5 — beans·brews 인자는 V-14·V-15).
 * 외부 호출 없음 — 순수 도메인 검증(모듈 CLAUDE.md §5.2).
 * <p>진입점 분할(changes/0024 TΔ2b2) 후에도 한 파일로 유지한다 — 규칙 패밀리·픽스처를 공유하고,
 * 두 진입점을 가로지르는 단언(V-16 게이트 record 전용, 비정상 pending 양 경로 수렴)이 있어서다.
 * 분할이 배치 변경임은 이 테스트 전량 그린이 가드한다.
 */
class ProposalValidatorsTest {

    private static final LocalDate TASTED = LocalDate.of(2026, 7, 16);
    // 연도 없는 표기 해석(V-16)의 기준 시계 — 시스템 시계 대신 고정 시계(2026-07-22)로 결정론화.
    private static final Clock FIXED = Clock.fixed(
            LocalDate.of(2026, 7, 22).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul"));
    private final RecordProposalValidator recordValidator = new RecordProposalValidator(FIXED);
    private final EditProposalValidator editValidator = new EditProposalValidator();

    // ---- 픽스처 ----

    private static BrewArg tastingBrew(String myTaste, String myTasteOriginal, String rating) {
        return new BrewArg(null, new BrewArg.TastingArg(myTaste, myTasteOriginal, rating));
    }

    private static ProposeRecordArgs recordArgs(String coffeeName, String rating, String targetDate,
                                                ProposeRecordArgs.MatchArg match) {
        return new ProposeRecordArgs(
                new SourcedArg<>(coffeeName, "user"),
                new SourcedArg<>("커피베라", "user"),
                List.of(new BeanArg(new SourcedArg<>("에티오피아", "search"), new SourcedArg<>(null, null))),
                new SourcedArg<>(null, null),
                new SourcedArg<>(List.of("자스민", "베르가못"), "search"),
                List.of(new BrewArg(new Recipe(null, 15.0, 240.0, null, null, null, null, null, null, null),
                        new BrewArg.TastingArg("새콤하고 좋았음", "새콤하고 좋았다", rating))),
                targetDate, match,
                List.of("https://frob.co.kr/products/chelbesa"));
    }

    private static ProposeRecordArgs recordArgs() {
        return recordArgs("커피베라 예가체프 G1", "맛있다", TASTED.toString(),
                new ProposeRecordArgs.MatchArg("new", null, null));
    }

    /** 메타 최소·brews만 바꿔 끼우는 변형 — beans·brews 검증 케이스용. */
    private static ProposeRecordArgs recordArgsWith(List<BeanArg> beans, List<BrewArg> brews) {
        return new ProposeRecordArgs(
                new SourcedArg<>("예가체프", "user"), null, beans, null, null, brews,
                TASTED.toString(), new ProposeRecordArgs.MatchArg("new", null, null), null);
    }

    private static Note note(String slug, String coffeeName, String roastery, LocalDate... entryDates) {
        List<Entry> entries = Arrays.stream(entryDates)
                .map(d -> new Entry(
                        d, List.of(new Brew(null, new Tasting("맛", null, Rating.GOOD))), OffsetDateTime.now()))
                .toList();
        return new Note(slug, new Sourced<>(coffeeName, Source.USER),
                roastery == null ? null : new Sourced<>(roastery, Source.USER),
                List.of(), null, null, List.of(), entries,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static PendingNote recordPending(String coffeeName) {
        return recordPending(coffeeName, "커피베라");
    }

    private static PendingNote recordPending(String coffeeName, String roastery) {
        return new PendingNote(note("2026-07-16-100000", coffeeName, roastery, TASTED),
                MatchInfo.newNote(), "ts-1", OffsetDateTime.now());
    }

    private static PendingNote editPending(String slug, LocalDate targetDate) {
        return new PendingNote(PendingNote.Mode.EDIT, note(slug, "커피베라 예가체프 G1", "커피베라", targetDate),
                new PendingNote.EditTarget(slug, targetDate), null, "ts-1", OffsetDateTime.now());
    }

    private static ProposeEditArgs editArgs(String slug, String date, ProposeEditArgs.Patch patch) {
        return new ProposeEditArgs(slug, date, patch);
    }

    private static ProposeEditArgs.Patch patchWithNewDate(String newDate) {
        return new ProposeEditArgs.Patch(null, null, null, null, null, newDate);
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
    private ToolValidation<RecordProposal> validateRecord(ProposeRecordArgs args, PendingNote pending) {
        return recordValidator.validate(args, pending, new TurnUserMessage("7월 16일 새콤하고 좋았음", null));
    }

    // ---- TΔ2b 턴 원문 배선 ----

    @Nested
    class TurnUserMessageWiringT2b {

        @Test
        @DisplayName("TΔ2b: 게이트 비발동 원문(null·단일 날짜)이면 판정 결과는 동일하다 — 배선 자체는 판정에 영향 없음")
        void utteranceWiringDoesNotAffectJudgement() {
            RecordProposal withoutUtterance = okOf(recordValidator.validate(recordArgs(), null, null));
            RecordProposal withSingleDate = okOf(recordValidator.validate(recordArgs(), null,
                    new TurnUserMessage("7월 16일 새콤하고 좋았음", null)));
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
            String reason = rejectionOf(recordValidator.validate(recordArgs(), null,
                    new TurnUserMessage("7월 16일은 새콤했고 7월 17일은 고소했음", null)));
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
                    null, new TurnUserMessage("7월 16일은 새콤했고 7월 17일은 고소했음", segments)));
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
            RecordProposal earliest = okOf(recordValidator.validate(recordArgs(), null, utterance));
            assertThat(earliest.targetDate()).isEqualTo(LocalDate.of(2026, 7, 16));

            // 게이트 기준은 집합 소속뿐 — 이른 날짜 강제는 프롬프트 몫이라 "저장 후 이어서" 턴의
            // 나중 날짜 제안도 게이트에 막히지 않는다(ADR-60).
            RecordProposal later = okOf(recordValidator.validate(
                    recordArgs("커피베라 예가체프 G1", "맛있다", "2026-07-17",
                            new ProposeRecordArgs.MatchArg("new", null, null)),
                    null, utterance));
            assertThat(later.targetDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        }

        @Test
        @DisplayName("V-16/ADR-60: 상대 날짜는 세지 않는다 — 절대 날짜 1개 + 상대 날짜 발화는 게이트 비발동")
        void relativeDatesDoNotTriggerGate() {
            okOf(recordValidator.validate(recordArgs(), null,
                    new TurnUserMessage("어제는 별로였는데 7월 16일은 새콤하고 좋았음", null)));
        }

        @Test
        @DisplayName("AC-Δ4: 날짜 2개(대상 date + new_date 이동)의 propose_edit는 게이트에 걸리지 않고 통과한다 — V-16 record 전용")
        void editWithTwoDatesPassesUngated() {
            Note target = note("2026-07-13-102030", "커피베라 예가체프 G1", "커피베라",
                    LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 14));
            // POLICY 대응: 다중 날짜 게이트는 record 전용 — edit는 날짜 정정·이동이 날짜 2개를 정당하게 포함(ADR-60).
            EditProposal proposal = okOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", patchWithNewDate("2026-07-15")), target, null));
            assertThat(proposal.newDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        }
    }

    // ---- V-1 rating ----

    @Nested
    class RatingV1 {

        @Test
        @DisplayName("V-1/AC-9: 회차 tasting의 rating 4범주 밖 값은 사유(허용값 안내)와 함께 거부된다")
        void invalidRatingRejectedWithReason() {
            ToolValidation<RecordProposal> result = validateRecord(
                    recordArgs("예가체프", "그냥 그래", TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("new", null, null)), null);
            assertThat(rejectionOf(result))
                    .contains("rating").contains("그냥 그래")
                    .contains("완전 내스타일").contains("V-1");
        }

        @Test
        @DisplayName("V-1: rating null(미언급)과 정확한 4범주 라벨은 통과한다")
        void nullAndValidRatingPass() {
            RecordProposal withNull = okOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("new", null, null)), null));
            assertThat(withNull.brews().getFirst().tasting().rating()).isNull();

            RecordProposal withLabel = okOf(validateRecord(recordArgs(), null));
            assertThat(withLabel.brews().getFirst().tasting().rating()).isEqualTo(Rating.GOOD);
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
            assertThat(rejectionOf(validateRecord(args, null)))
                    .contains("coffee_name").contains("user|photo").contains("V-5");
        }

        @Test
        @DisplayName("V-5: 정의 밖 source 값은 사유와 함께 거부된다")
        void unknownSourceRejected() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"),
                    new SourcedArg<>("커피베라", "guess"), null, null, null, null,
                    TASTED.toString(), new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(rejectionOf(validateRecord(args, null)))
                    .contains("roastery").contains("guess").contains("user|photo|search");
        }

        @Test
        @DisplayName("V-5: 값이 있는데 source 자기 보고가 없으면 거부된다(ADR-45)")
        void valuePresentWithoutSourceRejected() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"),
                    new SourcedArg<>("커피베라", null), null, null, null, null,
                    TASTED.toString(), new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(rejectionOf(validateRecord(args, null)))
                    .contains("roastery").contains("source");
        }

        @Test
        @DisplayName("V-5: 빈 값 필드는 source와 무관하게 null Sourced로 정규화된다(추측 금지)")
        void emptyValueNormalizedToNull() {
            RecordProposal proposal = okOf(validateRecord(recordArgs(), null));
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
                    List.of(tastingBrew("좋았음", null, null))), null));
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
                    List.of(tastingBrew("좋았음", null, null))), null));
            assertThat(proposal.meta().beans())
                    .containsExactly(new Bean(new Sourced<>("콜롬비아", Source.USER), null));
        }

        @Test
        @DisplayName("V-14/V-5: beans 요소 서브필드의 source 위반은 요소 위치를 짚은 사유와 함께 거부된다")
        void beanSubfieldSourceViolationRejected() {
            assertThat(rejectionOf(validateRecord(recordArgsWith(
                    List.of(new BeanArg(new SourcedArg<>("에티오피아", "guess"), new SourcedArg<>(null, null))),
                    List.of(tastingBrew("좋았음", null, null))), null)))
                    .contains("beans[0].description").contains("guess").contains("V-5");
        }

        @Test
        @DisplayName("V-14: beans 미언급(null)은 빈 배열로 정규화된다 — 저장 거부 아님(원두 정보는 부속)")
        void missingBeansNormalizedToEmpty() {
            RecordProposal proposal = okOf(validateRecord(
                    recordArgsWith(null, List.of(tastingBrew("좋았음", null, null))), null));
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
                    null, List.of(new BrewArg(new Recipe(null, -1.0, 240.0, null, null, null, "  ", null, null, null), null))), null));
            // 감상 없는 recipe만의 발화 = recipe만 담긴 회차 1개(V-15 허용).
            assertThat(proposal.brews().getFirst().recipe()).isEqualTo(new Recipe(null, null, 240.0, null, null, null, null, null, null, null));
            assertThat(proposal.brews().getFirst().tasting()).isNull();
        }

        @Test
        @DisplayName("V-8: 확장 10필드(수치 양수·텍스트 공백)에도 항목 단위 드롭이 적용된다(changes/0021)")
        void tenFieldNormalizationAppliesPerItem() {
            Recipe raw = new Recipe("에스프레소", 18.0, null, -1.0, 28.0, 0.0, "8 (매버릭 2.0)", " ", null, "다음엔 220클릭");
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(new BrewArg(raw, null))), null));
            assertThat(proposal.brews().getFirst().recipe()).isEqualTo(
                    new Recipe("에스프레소", 18.0, null, null, 28.0, null, "8 (매버릭 2.0)", null, null, "다음엔 220클릭"));
        }

        @Test
        @DisplayName("V-8: recipe 전 필드 전무면 recipe 자체가 null로 정규화된다(레시피 카드 미생성 근거)")
        void allInvalidNormalizedToNull() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(new BrewArg(new Recipe(null, 0.0, null, null, null, null, "", null, null, null),
                            new BrewArg.TastingArg("좋았음", null, null)))), null));
            // recipe 전무 정규화가 저장 거부로 번지지 않는다 — 감상 tasting이 있어 회차는 성립(V-15).
            assertThat(proposal.brews().getFirst().recipe()).isNull();
        }
    }

    // ---- V-15 회차(brews) — changes/0021 ADR-59 ----

    @Nested
    class BrewsV15 {

        @Test
        @DisplayName("V-15: brews 미언급(null)은 회차 0개라 사유와 함께 거부된다 — 기록할 내용이 없음")
        void missingBrewsRejected() {
            assertThat(rejectionOf(validateRecord(recordArgsWith(null, null), null)))
                    .contains("회차").contains("V-15");
        }

        @Test
        @DisplayName("V-15: 전 요소가 빈 회차(드롭)여도 회차 0개로 거부된다")
        void allEmptyBrewsRejected() {
            assertThat(rejectionOf(validateRecord(recordArgsWith(
                    null, List.of(new BrewArg(null, null),
                            new BrewArg(new Recipe(null, 0.0, null, null, null, null, " ", null, null, null), new BrewArg.TastingArg(" ", null, null)))),
                    null))).contains("회차").contains("V-15");
        }

        @Test
        @DisplayName("V-15/AC-74: 시도 2회 발화는 회차 2개로 정규화된다 — 배열 순서 = 회차 번호, 시도별 recipe·tasting 유지")
        void twoAttemptsBecomeTwoBrews() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(null, List.of(
                    new BrewArg(new Recipe(null, 15.0, 240.0, null, null, null, "210클릭 (매버릭 2.0)", null, null, null),
                            new BrewArg.TastingArg("떫었음", "떫었다", null)),
                    new BrewArg(new Recipe(null, 15.0, 240.0, null, null, null, "220클릭 (매버릭 2.0)", null, null, null),
                            new BrewArg.TastingArg("부드러웠음", "부드러웠다", "맛있다")))), null));
            assertThat(proposal.brews()).containsExactly(
                    new Brew(new Recipe(null, 15.0, 240.0, null, null, null, "210클릭 (매버릭 2.0)", null, null, null), new Tasting("떫었음", "떫었다", null)),
                    new Brew(new Recipe(null, 15.0, 240.0, null, null, null, "220클릭 (매버릭 2.0)", null, null, null),
                            new Tasting("부드러웠음", "부드러웠다", Rating.GOOD)));
        }

        @Test
        @DisplayName("V-15: 빈 회차 요소만 드롭되고 내용 있는 회차는 유지된다")
        void emptyBrewElementDropped() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(new BrewArg(null, null), tastingBrew("좋았음", null, null))), null));
            assertThat(proposal.brews()).containsExactly(new Brew(null, new Tasting("좋았음", null, null)));
        }

        @Test
        @DisplayName("V-15: 감상 발화 1건은 tasting을 담은 회차 1개로 정규화된다(V-11 원문 병존 포함)")
        void tasteBecomesSingleBrew() {
            RecordProposal proposal = okOf(validateRecord(recordArgs(), null));
            assertThat(proposal.brews()).containsExactly(new Brew(
                    new Recipe(null, 15.0, 240.0, null, null, null, null, null, null, null),
                    new Tasting("새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD)));
        }
    }

    // ---- V-11 my_taste 병존 ----

    @Nested
    class MyTasteV11 {

        @Test
        @DisplayName("V-11: 원문 누락 시 정규화본이 양쪽에 담긴다 — 저장 거부 아님(감상 유실 방지 우선)")
        void missingOriginalFallsBackToNormalized() {
            RecordProposal proposal = okOf(validateRecord(recordArgsWith(
                    null, List.of(tastingBrew("맛있었음", null, null))), null));
            Tasting tasting = proposal.brews().getFirst().tasting();
            assertThat(tasting.myTaste()).isEqualTo("맛있었음");
            assertThat(tasting.myTasteOriginal()).isEqualTo("맛있었음");
        }

        @Test
        @DisplayName("V-11: 원문이 오면 정규화본과 함께 보존된다(AC-47)")
        void originalPreservedWhenPresent() {
            RecordProposal proposal = okOf(validateRecord(recordArgs(), null));
            Tasting tasting = proposal.brews().getFirst().tasting();
            assertThat(tasting.myTaste()).isEqualTo("새콤하고 좋았음");
            assertThat(tasting.myTasteOriginal()).isEqualTo("새콤하고 좋았다");
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
                            new ProposeRecordArgs.MatchArg("new", null, null)), null)))
                    .contains("coffee_name");
        }

        @Test
        @DisplayName("target_date 부재·비형식은 YYYY-MM-DD 안내와 함께 거부된다(상대 날짜는 에이전트 해석)")
        void missingOrMalformedTargetDateRejected() {
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, null, new ProposeRecordArgs.MatchArg("new", null, null)),
                    null))).contains("target_date");
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, "엊그제", new ProposeRecordArgs.MatchArg("new", null, null)),
                    null))).contains("YYYY-MM-DD");
        }

        @Test
        @DisplayName("match 부재·미정의 type·slug 없는 existing은 각각 사유와 함께 거부된다(AC-15 근거)")
        void malformedMatchRejected() {
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(), null), null))).contains("match");
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("maybe", null, null)), null)))
                    .contains("new|existing");
            assertThat(rejectionOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("existing", null, TASTED.toString())), null)))
                    .contains("slug");
        }

        @Test
        @DisplayName("match existing 통과 시 MatchInfo.existing으로 변환된다")
        void existingMatchConverted() {
            RecordProposal proposal = okOf(validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("existing", "2026-07-13-102030", "2026-07-16")),
                    null));
            assertThat(proposal.match())
                    .isEqualTo(MatchInfo.existing("2026-07-13-102030", LocalDate.of(2026, 7, 16)));
        }
    }

    // ---- 단일 대기(FR-22/AC-30) — propose_record ----

    @Nested
    class SinglePendingRecord {

        @Test
        @DisplayName("AC-30: 확인 대기 중 다른 커피의 새 기록 제안은 '먼저 저장/취소' 사유로 거부된다")
        void differentCoffeeRejectedWhilePending() {
            String reason = rejectionOf(validateRecord(recordArgs(), recordPending("와이키키 블렌드")));
            assertThat(reason).contains("[저장]").contains("[취소]").contains("와이키키 블렌드");
        }

        @Test
        @DisplayName("FR-5: record 대기 중 같은 커피(이름+로스터리)의 재호출은 갱신 경로로 통과한다(정규화 대조)")
        void sameCoffeeReproposalPassesWhilePending() {
            okOf(validateRecord(recordArgs(), recordPending("커피베라 예가체프 G1")));
            // V-13 정규화 기준: 표기 차이(공백·대소문자)는 같은 커피다.
            okOf(validateRecord(recordArgs(), recordPending("커피베라예가체프 g1")));
        }

        @Test
        @DisplayName("같은 커피 판정은 이름+로스터리 — 이름이 같아도 로스터리가 다르면 다른 커피로 거부된다")
        void sameNameDifferentRoasteryRejected() {
            // 싱글 오리진은 이름이 로스터리 간 겹치기 쉽다(사용자 확정, data-model §3.3).
            assertThat(rejectionOf(validateRecord(
                    recordArgs(), recordPending("커피베라 예가체프 G1", "프릳츠"))))
                    .contains("[저장]");
        }

        @Test
        @DisplayName("한쪽 roastery가 비어 있으면 이름만 대조한다 — 대기 중 로스터리 보강·정정 갱신을 막지 않는다")
        void missingRoasteryFallsBackToNameOnly() {
            // 대기 draft에 로스터리가 아직 없고 재호출이 채워 오는 경우(FR-5 보강 갱신).
            okOf(validateRecord(recordArgs(), recordPending("커피베라 예가체프 G1", null)));
            // 반대로 재호출 쪽에 로스터리가 빠져 있어도 같은 커피다. (감상은 V-15 회차 성립용으로 채운다.)
            ProposeRecordArgs withoutRoastery = new ProposeRecordArgs(
                    new SourcedArg<>("커피베라 예가체프 G1", "user"), null, null, null, null,
                    List.of(tastingBrew("좋았음", null, null)), TASTED.toString(),
                    new ProposeRecordArgs.MatchArg("new", null, null), null);
            okOf(validateRecord(withoutRoastery, recordPending("커피베라 예가체프 G1")));
        }

        @Test
        @DisplayName("단일 대기: 수정 세션(edit pending) 중 새 기록 제안은 거부된다")
        void newRecordRejectedWhileEditPending() {
            assertThat(rejectionOf(validateRecord(
                    recordArgs(), editPending("2026-07-13-102030", TASTED))))
                    .contains("수정 세션");
        }

        @Test
        @DisplayName("대기 없음이면 새 기록 제안이 통과한다")
        void passesWithoutPending() {
            okOf(validateRecord(recordArgs(), null));
        }
    }

    // ---- propose_edit: 대상·V-10·beans/brews 교체·단일 대기 ----

    @Nested
    class EditProposals {

        private final Note target = note("2026-07-13-102030", "커피베라 예가체프 G1", "커피베라",
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 14));

        @Test
        @DisplayName("대상 엔트리가 없는 날짜의 수정 제안은 거부된다 — 환각 필터(data-model §3.4)")
        void missingEntryRejected() {
            assertThat(rejectionOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-12", ProposeEditArgs.Patch.empty()), target, null)))
                    .contains("2026-07-12").contains("엔트리가 없다");
        }

        @Test
        @DisplayName("§3.4: new_date 이동처는 도메인 날짜로 전달된다 — 충돌(V-10) 계산은 제안 수용 지점(ProposalTools) 몫")
        void dateMovePassedThrough() {
            EditProposal proposal = okOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", patchWithNewDate("2026-07-14")), target, null));
            assertThat(proposal.newDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        }

        @Test
        @DisplayName("V-10: 대상 자신의 날짜로는 이동이 아니다 — newDate null로 정규화")
        void moveToOwnDateIsNotAMove() {
            EditProposal proposal = okOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", patchWithNewDate("2026-07-13")), target, null));
            assertThat(proposal.newDate()).isNull();
        }

        @Test
        @DisplayName("FR-5: edit 대기 중 같은 대상(slug+date)의 재호출은 갱신 경로로 통과한다")
        void sameTargetReproposalPasses() {
            okOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", ProposeEditArgs.Patch.empty()),
                    target, editPending(target.slug(), LocalDate.of(2026, 7, 13))));
        }

        @Test
        @DisplayName("단일 대기: 대기 중 다른 대상의 수정 제안은 '먼저 저장/취소' 사유로 거부된다")
        void differentTargetRejectedWhilePending() {
            // 같은 노트의 다른 엔트리도 다른 대상이다.
            assertThat(rejectionOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-14", ProposeEditArgs.Patch.empty()),
                    target, editPending(target.slug(), LocalDate.of(2026, 7, 13)))))
                    .contains("[저장]");
            // record 대기 중 수정 세션 제안도 거부(구 수정 진입 거부 승계).
            assertThat(rejectionOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", ProposeEditArgs.Patch.empty()),
                    target, recordPending("와이키키 블렌드"))))
                    .contains("[저장]");
        }

        @Test
        @DisplayName("단일 대기: target이 빠진 edit pending(비정상 역직렬화)도 NPE 없이 사유 있는 거부로 수렴한다 — ADR-45 POLICY")
        void editPendingWithoutTargetStillRejectsWithReason() {
            // mode=edit인데 target=null — pending.json 수기 편집 등으로만 가능한 비정상 상태.
            PendingNote corrupt = new PendingNote(PendingNote.Mode.EDIT,
                    note("2026-07-13-102030", "커피베라 예가체프 G1", "커피베라", LocalDate.of(2026, 7, 13)),
                    null, null, "ts-1", OffsetDateTime.now());
            assertThat(rejectionOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", ProposeEditArgs.Patch.empty()), target, corrupt)))
                    .contains("수정 세션").contains("[저장]");
            assertThat(rejectionOf(validateRecord(recordArgs(), corrupt)))
                    .contains("수정 세션").contains("[저장]");
        }

        @Test
        @DisplayName("단일 대기: draft가 빠진 record pending(비정상 역직렬화)도 NPE 없이 사유 있는 거부로 수렴한다 — ADR-45 POLICY")
        void recordPendingWithoutDraftStillRejectsWithReason() {
            // mode=record인데 draft=null — pending.json 수기 편집 등으로만 가능한 비정상 상태(edit의 target null과 대칭).
            PendingNote corrupt = new PendingNote(PendingNote.Mode.RECORD, null, null,
                    MatchInfo.newNote(), "ts-1", OffsetDateTime.now());
            assertThat(rejectionOf(validateRecord(recordArgs(), corrupt)))
                    .contains("새 기록").contains("대상 미상").contains("[저장]");
            assertThat(rejectionOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", ProposeEditArgs.Patch.empty()), target, corrupt)))
                    .contains("새 기록").contains("대상 미상").contains("[저장]");
        }

        @Test
        @DisplayName("§3.4: patch의 brews는 통째 교체로 정규화되어 도착한다 — 빈 회차 드롭 포함(V-15)")
        void patchBrewsReplacedWhole() {
            ProposeEditArgs.Patch patch = new ProposeEditArgs.Patch(null, null, null, null,
                    List.of(new BrewArg(null, null), tastingBrew("더 새콤했음", "더 새콤했다", "완전 내스타일")),
                    null);
            EditProposal proposal = okOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", patch), target, null));
            assertThat(proposal.brews()).containsExactly(
                    new Brew(null, new Tasting("더 새콤했음", "더 새콤했다", Rating.PERFECT)));
        }

        @Test
        @DisplayName("V-15: 교체 결과 회차 0개가 되는 brews patch는 사유와 함께 거부된다")
        void patchBrewsEmptyAfterNormalizeRejected() {
            ProposeEditArgs.Patch emptyArray = new ProposeEditArgs.Patch(null, null, null, null, List.of(), null);
            assertThat(rejectionOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", emptyArray), target, null)))
                    .contains("회차").contains("V-15");

            ProposeEditArgs.Patch allEmpty = new ProposeEditArgs.Patch(null, null, null, null,
                    List.of(new BrewArg(null, null)), null);
            assertThat(rejectionOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", allEmpty), target, null)))
                    .contains("회차").contains("V-15");
        }

        @Test
        @DisplayName("§3.4: patch의 beans도 통째 교체(V-14 정규화)로 도착한다 — 미언급(null)은 유지")
        void patchBeansReplacedWhole() {
            ProposeEditArgs.Patch patch = new ProposeEditArgs.Patch(null,
                    List.of(new BeanArg(new SourcedArg<>("콜롬비아", "user"), new SourcedArg<>(" ", "user"))),
                    null, null, null, null);
            EditProposal proposal = okOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", patch), target, null));
            assertThat(proposal.beans()).containsExactly(new Bean(new Sourced<>("콜롬비아", Source.USER), null));
        }

        @Test
        @DisplayName("patch의 rating 위반(V-1)은 record와 동일하게 사유와 함께 거부된다")
        void patchRatingViolationRejected() {
            ProposeEditArgs.Patch patch = new ProposeEditArgs.Patch(null, null, null, null,
                    List.of(tastingBrew("맛", null, "최고")), null);
            assertThat(rejectionOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", patch), target, null)))
                    .contains("rating").contains("V-1");
        }

        @Test
        @DisplayName("빈 patch(순수 전환)는 전 필드 유지(null)로 통과한다")
        void emptyPatchPasses() {
            EditProposal proposal = okOf(editValidator.validate(
                    editArgs(target.slug(), "2026-07-13", null), target, null));
            assertThat(proposal.roastery()).isNull();
            assertThat(proposal.beans()).isNull();
            assertThat(proposal.brews()).isNull();
            assertThat(proposal.newDate()).isNull();
        }

    }
}
