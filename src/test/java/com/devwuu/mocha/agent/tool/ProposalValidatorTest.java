package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ1(changes/0018): 제안 tool 서버 검증 — 검증 규칙별 통과/거부(사유 포함)를 결정론으로 단언한다
 * (AC-Δ5, data-model §5). 외부 호출 없음 — 순수 도메인 검증(모듈 CLAUDE.md §5.2).
 */
class ProposalValidatorTest {

    private static final LocalDate TASTED = LocalDate.of(2026, 7, 16);
    private final ProposalValidator validator = new ProposalValidator();

    // ---- 픽스처 ----

    private static ProposeRecordArgs recordArgs(String coffeeName, String rating, String targetDate,
                                                ProposeRecordArgs.MatchArg match) {
        return new ProposeRecordArgs(
                new SourcedArg<>(coffeeName, "user"),
                new SourcedArg<>("커피베라", "user"),
                new SourcedArg<>("에티오피아", "search"),
                new SourcedArg<>(null, null),
                new SourcedArg<>(null, null),
                new SourcedArg<>(List.of("자스민", "베르가못"), "search"),
                "새콤하고 좋았음", "새콤하고 좋았다", rating,
                new Recipe(15.0, 240.0, null),
                targetDate, match,
                List.of("https://frob.co.kr/products/chelbesa"));
    }

    private static ProposeRecordArgs recordArgs() {
        return recordArgs("커피베라 예가체프 G1", "맛있다", TASTED.toString(),
                new ProposeRecordArgs.MatchArg("new", null, null));
    }

    private static Note note(String slug, String coffeeName, String roastery, LocalDate... entryDates) {
        List<com.devwuu.mocha.domain.Entry> entries = java.util.Arrays.stream(entryDates)
                .map(d -> new com.devwuu.mocha.domain.Entry(d, "맛", Rating.GOOD, null, OffsetDateTime.now()))
                .toList();
        return new Note(slug, Sourced.user(coffeeName),
                roastery == null ? null : Sourced.user(roastery),
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

    private static String rejectionOf(ToolValidation<?> result) {
        assertThat(result).isInstanceOf(ToolValidation.Rejected.class);
        return ((ToolValidation.Rejected<?>) result).reason();
    }

    private static <T> T okOf(ToolValidation<T> result) {
        assertThat(result).isInstanceOf(ToolValidation.Ok.class);
        return ((ToolValidation.Ok<T>) result).value();
    }

    // ---- V-1 rating ----

    @Nested
    class RatingV1 {

        @Test
        @DisplayName("V-1/AC-9: rating 4범주 밖 값은 사유(허용값 안내)와 함께 거부된다")
        void invalidRatingRejectedWithReason() {
            ToolValidation<RecordProposal> result = validator.validateRecord(
                    recordArgs("예가체프", "그냥 그래", TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("new", null, null)), null);
            assertThat(rejectionOf(result))
                    .contains("rating").contains("그냥 그래")
                    .contains("완전 내스타일").contains("V-1");
        }

        @Test
        @DisplayName("V-1: rating null(미언급)과 정확한 4범주 라벨은 통과한다")
        void nullAndValidRatingPass() {
            RecordProposal withNull = okOf(validator.validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("new", null, null)), null));
            assertThat(withNull.rating()).isNull();

            RecordProposal withLabel = okOf(validator.validateRecord(recordArgs(), null));
            assertThat(withLabel.rating()).isEqualTo(Rating.GOOD);
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
                    null, null, null, null, TASTED.toString(),
                    new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(rejectionOf(validator.validateRecord(args, null)))
                    .contains("coffee_name").contains("user|photo").contains("V-5");
        }

        @Test
        @DisplayName("V-5: 정의 밖 source 값은 사유와 함께 거부된다")
        void unknownSourceRejected() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"),
                    new SourcedArg<>("커피베라", "guess"), null, null, null, null,
                    null, null, null, null, TASTED.toString(),
                    new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(rejectionOf(validator.validateRecord(args, null)))
                    .contains("roastery").contains("guess").contains("user|photo|search");
        }

        @Test
        @DisplayName("V-5: 값이 있는데 source 자기 보고가 없으면 거부된다(ADR-45)")
        void valuePresentWithoutSourceRejected() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"),
                    new SourcedArg<>("커피베라", null), null, null, null, null,
                    null, null, null, null, TASTED.toString(),
                    new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(rejectionOf(validator.validateRecord(args, null)))
                    .contains("roastery").contains("source");
        }

        @Test
        @DisplayName("V-5: 빈 값 필드는 source와 무관하게 null Sourced로 정규화된다(추측 금지)")
        void emptyValueNormalizedToNull() {
            RecordProposal proposal = okOf(validator.validateRecord(recordArgs(), null));
            assertThat(proposal.meta().roastLevel()).isNull();
            // 과도기 shim(TΔ1a): origin/process 인자는 beans 요소로 변환된다 — 빈 process는 null 정규화(V-14).
            assertThat(proposal.meta().beans())
                    .containsExactly(new com.devwuu.mocha.domain.Bean(Sourced.search("에티오피아"), null));
            assertThat(proposal.meta().officialNotes().value()).containsExactly("자스민", "베르가못");
            assertThat(proposal.meta().officialNotes().source()).isEqualTo(Source.SEARCH);
        }
    }

    // ---- V-8 recipe ----

    @Nested
    class RecipeV8 {

        @Test
        @DisplayName("V-8: 위반 값(음수·0·공백)은 항목만 드롭되고 저장은 거부되지 않는다")
        void invalidItemsDroppedNotRejected() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"), null, null, null, null, null,
                    null, null, null, new Recipe(-1.0, 240.0, "  "), TASTED.toString(),
                    new ProposeRecordArgs.MatchArg("new", null, null), null);
            RecordProposal proposal = okOf(validator.validateRecord(args, null));
            assertThat(proposal.recipe()).isEqualTo(new Recipe(null, 240.0, null));
        }

        @Test
        @DisplayName("V-8: 3항목 전무면 recipe 자체가 null로 정규화된다(카드 레시피 영역 미표시 근거)")
        void allInvalidNormalizedToNull() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"), null, null, null, null, null,
                    null, null, null, new Recipe(0.0, null, ""), TASTED.toString(),
                    new ProposeRecordArgs.MatchArg("new", null, null), null);
            assertThat(okOf(validator.validateRecord(args, null)).recipe()).isNull();
        }
    }

    // ---- V-11 my_taste 병존 ----

    @Nested
    class MyTasteV11 {

        @Test
        @DisplayName("V-11: 원문 누락 시 정규화본이 양쪽에 담긴다 — 저장 거부 아님(감상 유실 방지 우선)")
        void missingOriginalFallsBackToNormalized() {
            ProposeRecordArgs args = new ProposeRecordArgs(
                    new SourcedArg<>("예가체프", "user"), null, null, null, null, null,
                    "맛있었음", null, null, null, TASTED.toString(),
                    new ProposeRecordArgs.MatchArg("new", null, null), null);
            RecordProposal proposal = okOf(validator.validateRecord(args, null));
            assertThat(proposal.myTaste()).isEqualTo("맛있었음");
            assertThat(proposal.myTasteOriginal()).isEqualTo("맛있었음");
        }

        @Test
        @DisplayName("V-11: 원문이 오면 정규화본과 함께 보존된다(AC-47)")
        void originalPreservedWhenPresent() {
            RecordProposal proposal = okOf(validator.validateRecord(recordArgs(), null));
            assertThat(proposal.myTaste()).isEqualTo("새콤하고 좋았음");
            assertThat(proposal.myTasteOriginal()).isEqualTo("새콤하고 좋았다");
        }
    }

    // ---- 인자 형식(날짜·match·coffee_name) ----

    @Nested
    class ArgumentShape {

        @Test
        @DisplayName("coffee_name이 비어 있으면 거부된다 — 기록의 정체성 앵커(data-model §2.1)")
        void blankCoffeeNameRejected() {
            assertThat(rejectionOf(validator.validateRecord(
                    recordArgs("  ", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("new", null, null)), null)))
                    .contains("coffee_name");
        }

        @Test
        @DisplayName("target_date 부재·비형식은 YYYY-MM-DD 안내와 함께 거부된다(상대 날짜는 에이전트 해석)")
        void missingOrMalformedTargetDateRejected() {
            assertThat(rejectionOf(validator.validateRecord(
                    recordArgs("예가체프", null, null, new ProposeRecordArgs.MatchArg("new", null, null)),
                    null))).contains("target_date");
            assertThat(rejectionOf(validator.validateRecord(
                    recordArgs("예가체프", null, "엊그제", new ProposeRecordArgs.MatchArg("new", null, null)),
                    null))).contains("YYYY-MM-DD");
        }

        @Test
        @DisplayName("match 부재·미정의 type·slug 없는 existing은 각각 사유와 함께 거부된다(AC-15 근거)")
        void malformedMatchRejected() {
            assertThat(rejectionOf(validator.validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(), null), null))).contains("match");
            assertThat(rejectionOf(validator.validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("maybe", null, null)), null)))
                    .contains("new|existing");
            assertThat(rejectionOf(validator.validateRecord(
                    recordArgs("예가체프", null, TASTED.toString(),
                            new ProposeRecordArgs.MatchArg("existing", null, TASTED.toString())), null)))
                    .contains("slug");
        }

        @Test
        @DisplayName("match existing 통과 시 MatchInfo.existing으로 변환된다")
        void existingMatchConverted() {
            RecordProposal proposal = okOf(validator.validateRecord(
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
            String reason = rejectionOf(validator.validateRecord(recordArgs(), recordPending("와이키키 블렌드")));
            assertThat(reason).contains("[저장]").contains("[취소]").contains("와이키키 블렌드");
        }

        @Test
        @DisplayName("FR-5: record 대기 중 같은 커피(이름+로스터리)의 재호출은 갱신 경로로 통과한다(정규화 대조)")
        void sameCoffeeReproposalPassesWhilePending() {
            okOf(validator.validateRecord(recordArgs(), recordPending("커피베라 예가체프 G1")));
            // V-13 정규화 기준: 표기 차이(공백·대소문자)는 같은 커피다.
            okOf(validator.validateRecord(recordArgs(), recordPending("커피베라예가체프 g1")));
        }

        @Test
        @DisplayName("같은 커피 판정은 이름+로스터리 — 이름이 같아도 로스터리가 다르면 다른 커피로 거부된다")
        void sameNameDifferentRoasteryRejected() {
            // 싱글 오리진은 이름이 로스터리 간 겹치기 쉽다(사용자 확정, data-model §3.3).
            assertThat(rejectionOf(validator.validateRecord(
                    recordArgs(), recordPending("커피베라 예가체프 G1", "프릳츠"))))
                    .contains("[저장]");
        }

        @Test
        @DisplayName("한쪽 roastery가 비어 있으면 이름만 대조한다 — 대기 중 로스터리 보강·정정 갱신을 막지 않는다")
        void missingRoasteryFallsBackToNameOnly() {
            // 대기 draft에 로스터리가 아직 없고 재호출이 채워 오는 경우(FR-5 보강 갱신).
            okOf(validator.validateRecord(recordArgs(), recordPending("커피베라 예가체프 G1", null)));
            // 반대로 재호출 쪽에 로스터리가 빠져 있어도 같은 커피다.
            ProposeRecordArgs withoutRoastery = new ProposeRecordArgs(
                    new SourcedArg<>("커피베라 예가체프 G1", "user"), null, null, null, null, null,
                    null, null, null, null, TASTED.toString(),
                    new ProposeRecordArgs.MatchArg("new", null, null), null);
            okOf(validator.validateRecord(withoutRoastery, recordPending("커피베라 예가체프 G1")));
        }

        @Test
        @DisplayName("단일 대기: 수정 세션(edit pending) 중 새 기록 제안은 거부된다")
        void newRecordRejectedWhileEditPending() {
            assertThat(rejectionOf(validator.validateRecord(
                    recordArgs(), editPending("2026-07-13-102030", TASTED))))
                    .contains("수정 세션");
        }

        @Test
        @DisplayName("대기 없음이면 새 기록 제안이 통과한다")
        void passesWithoutPending() {
            okOf(validator.validateRecord(recordArgs(), null));
        }
    }

    // ---- propose_edit: 대상·V-10·단일 대기 ----

    @Nested
    class EditProposals {

        private final Note target = note("2026-07-13-102030", "커피베라 예가체프 G1", "커피베라",
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 14));

        @Test
        @DisplayName("대상 엔트리가 없는 날짜의 수정 제안은 거부된다 — 환각 필터(data-model §3.4)")
        void missingEntryRejected() {
            assertThat(rejectionOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-12", ProposeEditArgs.Patch.empty()), target, null)))
                    .contains("2026-07-12").contains("엔트리가 없다");
        }

        @Test
        @DisplayName("V-10: 이동처에 기존 엔트리가 있으면 dateConflict=true로 계산된다(경고 없는 덮어쓰기 금지)")
        void dateMoveConflictComputed() {
            EditProposal conflicted = okOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-13", patchWithNewDate("2026-07-14")), target, null));
            assertThat(conflicted.newDate()).isEqualTo(LocalDate.of(2026, 7, 14));
            assertThat(conflicted.dateConflict()).isTrue();

            EditProposal free = okOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-13", patchWithNewDate("2026-07-20")), target, null));
            assertThat(free.dateConflict()).isFalse();
        }

        @Test
        @DisplayName("V-10: 대상 자신의 날짜로는 이동이 아니다 — newDate null·충돌 없음으로 정규화")
        void moveToOwnDateIsNotAMove() {
            EditProposal proposal = okOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-13", patchWithNewDate("2026-07-13")), target, null));
            assertThat(proposal.newDate()).isNull();
            assertThat(proposal.dateConflict()).isFalse();
        }

        @Test
        @DisplayName("FR-5: edit 대기 중 같은 대상(slug+date)의 재호출은 갱신 경로로 통과한다")
        void sameTargetReproposalPasses() {
            okOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-13", ProposeEditArgs.Patch.empty()),
                    target, editPending(target.slug(), LocalDate.of(2026, 7, 13))));
        }

        @Test
        @DisplayName("단일 대기: 대기 중 다른 대상의 수정 제안은 '먼저 저장/취소' 사유로 거부된다")
        void differentTargetRejectedWhilePending() {
            // 같은 노트의 다른 엔트리도 다른 대상이다.
            assertThat(rejectionOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-14", ProposeEditArgs.Patch.empty()),
                    target, editPending(target.slug(), LocalDate.of(2026, 7, 13)))))
                    .contains("[저장]");
            // record 대기 중 수정 세션 제안도 거부(구 수정 진입 거부 승계).
            assertThat(rejectionOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-13", ProposeEditArgs.Patch.empty()),
                    target, recordPending("와이키키 블렌드"))))
                    .contains("[저장]");
        }

        @Test
        @DisplayName("patch의 rating 위반(V-1)은 record와 동일하게 사유와 함께 거부된다")
        void patchRatingViolationRejected() {
            ProposeEditArgs.Patch patch = new ProposeEditArgs.Patch(
                    null, null, null, null, null, null, null, "최고", null, null);
            assertThat(rejectionOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-13", patch), target, null)))
                    .contains("rating").contains("V-1");
        }

        @Test
        @DisplayName("빈 patch(순수 전환)는 전 필드 유지(null)로 통과한다")
        void emptyPatchPasses() {
            EditProposal proposal = okOf(validator.validateEdit(
                    editArgs(target.slug(), "2026-07-13", null), target, null));
            assertThat(proposal.roastery()).isNull();
            assertThat(proposal.myTaste()).isNull();
            assertThat(proposal.rating()).isNull();
            assertThat(proposal.recipe()).isNull();
            assertThat(proposal.newDate()).isNull();
            assertThat(proposal.dateConflict()).isFalse();
        }

        private static ProposeEditArgs.Patch patchWithNewDate(String newDate) {
            return new ProposeEditArgs.Patch(null, null, null, null, null, null, null, null, null, newDate);
        }
    }
}
