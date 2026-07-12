package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmRequest;
import com.devwuu.mocha.slack.PreviewBlocks;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.TextObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-4: pending 수정 반영(FR-5/AC-5) 검증 — 수정 텍스트를 LLM 패치로 받아 draft에 병합하는 계약을
 * 결정론적으로 확인한다. LLM 생성은 fake로 대체하고(§5.3), 병합 규칙(부분 갱신·source=user 승격·엔트리 불변)과
 * 미리보기 표기(검색 태그 제거)를 단언한다.
 */
class PendingReviserTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);
    private static final OffsetDateTime TS = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");

    /** 요청을 포착하고 준비된 패치를 돌려주는 fake — SDK/실 API를 쓰지 않는다(§5.2). */
    static class CapturingLlmClient implements LlmClient {
        LlmRequest<?> captured;
        Object response;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T complete(LlmRequest<T> request) {
            this.captured = request;
            return (T) response;
        }
    }

    private PendingReviser reviser(CapturingLlmClient llm) {
        return new PendingReviser(llm, MochaObjectMapper.create());
    }

    /** origin=검색보강, 엔트리 1건을 담은 확인 대기 노트. */
    private static PendingNote pendingWithSearchOrigin() {
        Note draft = new Note(
                "coffeevera-yirgacheffe-g1", Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                Sourced.search("에티오피아"),   // 검색 보강값 — 수정으로 user 승격 대상
                null, null, null, List.of(),
                List.of(new Entry(DATE, "새콤함", Rating.GOOD, null, List.of(), TS)),
                TS, TS);
        return new PendingNote(draft, MatchInfo.newNote(), "1720000000.000999", TS);
    }

    /** mode=edit 확인 대기 노트 — 저장된 (slug, DATE) 엔트리를 고치는 수정 세션(changes/0012 TΔ5). */
    private static PendingNote editPending() {
        PendingNote base = pendingWithSearchOrigin();
        return new PendingNote(
                PendingNote.Mode.EDIT, base.draft(),
                new PendingNote.EditTarget(base.draft().slug(), DATE),
                null, base.previewTs(), base.createdAt());
    }

    private static RevisionResult noChange() {
        return new RevisionResult(null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("요청 조립: 현재 draft 값과 수정 요청이 사용자 프롬프트에 실린다")
    void assemblesRevisionRequest() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = noChange();

        reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야").pending();

        String prompt = llm.captured.userPrompt();
        assertThat(prompt).contains("커피베라 예가체프 G1"); // 현재 커피명
        assertThat(prompt).contains("에티오피아");           // 현재 origin(값만, 출처 표기 없이)
        assertThat(prompt).contains("current");             // snake_case 페이로드 키
        assertThat(prompt).contains("원산지는 콜롬비아야");   // 수정 요청 원문
    }

    @Test
    @DisplayName("검색 보강 필드를 수정하면 source=user로 승격된다")
    void promotesRevisedSearchFieldToUser() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // 사용자가 원산지를 직접 지정 → 패치에 origin만 실린다.
        llm.response = new RevisionResult(null, null, "콜롬비아", null, null, null, null, null, null);

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야").pending();

        assertThat(revised.draft().origin().value()).isEqualTo("콜롬비아");
        assertThat(revised.draft().origin().source()).isEqualTo(Source.USER); // 승격
    }

    @Test
    @DisplayName("AC-5: 수정은 엔트리를 새로 만들지 않는다 — 기존 엔트리 1건을 제자리 갱신")
    void keepsEntryCountUnchanged() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(null, null, null, null, null, null, "산미가 낮아 부드러웠다", null, null);

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "산미는 낮음으로").pending();

        List<Entry> entries = revised.draft().entries();
        assertThat(entries).hasSize(1);                       // 엔트리 미생성(AC-5)
        assertThat(entries.get(0).date()).isEqualTo(DATE);    // 같은 날짜 엔트리 유지
        assertThat(entries.get(0).myTaste()).isEqualTo("산미가 낮아 부드러웠다"); // 제자리 갱신
    }

    @Test
    @DisplayName("수정 요청과 무관한 필드는 그대로 유지된다 (null=변경 없음)")
    void keepsUnmentionedFieldsUnchanged() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(null, null, "콜롬비아", null, null, null, null, null, null);

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야").pending();

        Note draft = revised.draft();
        assertThat(draft.coffeeName().value()).isEqualTo("커피베라 예가체프 G1"); // 미변경
        assertThat(draft.roastery().value()).isEqualTo("커피베라");
        assertThat(draft.roastery().source()).isEqualTo(Source.USER);
        assertThat(draft.entries().get(0).myTaste()).isEqualTo("새콤함"); // 미변경
        assertThat(draft.entries().get(0).rating()).isEqualTo(Rating.GOOD);
        // match·preview_ts·created_at 보존 — 미리보기 edit 대상·TTL 기준 불변
        assertThat(revised.previewTs()).isEqualTo("1720000000.000999");
        assertThat(revised.createdAt()).isEqualTo(TS);
        assertThat(revised.match().type()).isEqualTo(MatchInfo.MatchType.NEW);
    }

    @Test
    @DisplayName("AC-2: 수정으로 user 승격된 필드는 미리보기에서 (검색) 표기가 사라진다")
    void removesSearchTagAfterRevisionInPreview() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(null, null, "콜롬비아", null, null, null, null, null, null);

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야").pending();

        // 수정된 pending을 그대로 미리보기 블록으로 조립 → origin 필드에 (검색) 표기가 없어야 한다.
        List<LayoutBlock> blocks = new PreviewBlocks().build(revised);
        String originField = findFieldText(blocks, "콜롬비아");
        assertThat(originField).isNotNull();
        assertThat(originField).contains("콜롬비아");
        assertThat(originField).doesNotContain("검색"); // (검색) 표기 제거(AC-2)
    }

    // --- changes/0012 TΔ5: edit 모드 — 커피명 거부(V-9)·날짜 이동(AC-39)·record 경로 불변(AC-Δ6) ---

    @Test
    @DisplayName("V-9/AC-38: edit 모드 커피명 변경 패치는 draft에 반영되지 않고 거부로 보고된다")
    void editModeRejectsCoffeeNameChange() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult("커피베라 예가체프 G2", null, null, null, null, null, null, null, null);

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(editPending(), "커피 이름 G2로 바꿔줘");

        assertThat(outcome.coffeeNameRejected()).isTrue(); // 그 턴의 거부 안내 재료(V-9)
        assertThat(outcome.pending().draft().coffeeName().value())
                .isEqualTo("커피베라 예가체프 G1"); // 오타 정정 포함 예외 없음 — draft 불변(AC-38)
        assertThat(outcome.pending().mode()).isEqualTo(PendingNote.Mode.EDIT); // 세션 종류 보존
    }

    @Test
    @DisplayName("V-9: edit 모드에서 같은 커피명 그대로는 변경이 아니다 — 거부로 치지 않는다")
    void editModeDoesNotRejectSameCoffeeNameEcho() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult("커피베라 예가체프 G1", null, null, null, null, null, null, null, null);

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(editPending(), "커피베라 예가체프 G1 맞아");

        assertThat(outcome.coffeeNameRejected()).isFalse();
        assertThat(outcome.pending().draft().coffeeName().value()).isEqualTo("커피베라 예가체프 G1");
    }

    @Test
    @DisplayName("AC-39 재료: edit 모드 date 패치는 이번 엔트리의 날짜 이동으로 반영된다(엔트리 개수 불변)")
    void editModeMovesEntryDate() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(null, null, null, null, null, null, null, null, LocalDate.of(2026, 7, 12));

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(editPending(), "이 기록 12일로 옮겨줘");

        List<Entry> entries = outcome.pending().draft().entries();
        assertThat(entries).hasSize(1); // 이동이지 복제가 아니다
        assertThat(entries.get(0).date()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(entries.get(0).myTaste()).isEqualTo("새콤함"); // 날짜만 이동 — 나머지 필드 보존
    }

    @Test
    @DisplayName("AC-Δ6(회귀 가드): record 모드는 종전과 동일 — 커피명 패치 반영, date 패치 무시")
    void recordModeKeepsLegacyBehavior() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(
                "커피베라 예가체프 G2", null, null, null, null, null, null, null, LocalDate.of(2026, 7, 12));

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(pendingWithSearchOrigin(), "이름은 G2야");

        assertThat(outcome.coffeeNameRejected()).isFalse(); // 거부는 edit 모드 한정(V-9)
        assertThat(outcome.pending().draft().coffeeName().value()).isEqualTo("커피베라 예가체프 G2"); // 종전대로 반영
        assertThat(outcome.pending().draft().entries().get(0).date())
                .isEqualTo(DATE); // 신규 기록 경로의 날짜는 수정 대상이 아니다(AC-Δ6)
    }

    // section fields 중 특정 값을 포함한 필드 텍스트를 찾는다.
    private static String findFieldText(List<LayoutBlock> blocks, String needle) {
        for (LayoutBlock block : blocks) {
            if (block instanceof SectionBlock section && section.getFields() != null) {
                for (TextObject field : section.getFields()) {
                    String text = ((com.slack.api.model.block.composition.MarkdownTextObject) field).getText();
                    if (text.contains(needle)) {
                        return text;
                    }
                }
            }
        }
        return null;
    }
}
