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
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 14);
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
                List.of(new Entry(DATE, "새콤함", Rating.GOOD, null, TS)),
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

        reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야", TODAY).pending();

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

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야", TODAY).pending();

        assertThat(revised.draft().origin().value()).isEqualTo("콜롬비아");
        assertThat(revised.draft().origin().source()).isEqualTo(Source.USER); // 승격
    }

    @Test
    @DisplayName("AC-5: 수정은 엔트리를 새로 만들지 않는다 — 기존 엔트리 1건을 제자리 갱신")
    void keepsEntryCountUnchanged() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(null, null, null, null, null, null, "산미가 낮아 부드러웠다", null, null);

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "산미는 낮음으로", TODAY).pending();

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

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야", TODAY).pending();

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

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야", TODAY).pending();

        // 수정된 pending을 그대로 미리보기 블록으로 조립 → origin 필드에 (검색) 표기가 없어야 한다.
        List<LayoutBlock> blocks = new PreviewBlocks().build(revised);
        String originField = findFieldText(blocks, "콜롬비아");
        assertThat(originField).isNotNull();
        assertThat(originField).contains("콜롬비아");
        assertThat(originField).doesNotContain("검색"); // (검색) 표기 제거(AC-2)
    }

    // --- changes/0013 TΔ4: my_taste 정규화 + my_taste_original 병존(ADR-30, V-11, AC-Δ5) ---

    @Test
    @DisplayName("AC-Δ5: 스키마·프롬프트가 my_taste(정규화)와 my_taste_original(원문)을 계약으로 선언한다 (ADR-30)")
    void schemaAndPromptDeclareMyTasteOriginalContract() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = noChange();

        reviser(llm).revise(pendingWithSearchOrigin(), "산미는 낮음으로", TODAY);

        LlmRequest<?> request = llm.captured;
        assertThat(request.jsonSchema()).contains("my_taste").contains("my_taste_original");
        assertThat(request.systemPrompt()).contains("음슴체").contains("my_taste_original");
    }

    @Test
    @DisplayName("AC-Δ5: 감상을 새로 말하면 my_taste와 my_taste_original이 함께 갱신된다 (ADR-30)")
    void revisesBothTasteFields() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // 사용자가 감상을 새로 말함 → LLM이 정규화본 + 원문을 함께 반환.
        llm.response = new RevisionResult(null, null, null, null, null, null,
                "산미가 낮아 부드러웠음", "산미가 낮아 부드러웠어", null, null);

        PendingNote revised = reviser(llm).revise(pendingWithSearchOrigin(), "산미는 낮음으로", TODAY).pending();

        Entry entry = revised.draft().entries().get(0);
        assertThat(entry.myTaste()).isEqualTo("산미가 낮아 부드러웠음");         // 정규화본
        assertThat(entry.myTasteOriginal()).isEqualTo("산미가 낮아 부드러웠어"); // 원문 동반 갱신
    }

    @Test
    @DisplayName("V-11: revise 패치가 원문을 누락하면 정규화본을 원문에도 복사한다 (감상 유실 방지)")
    void revisionCopiesNormalizedWhenOriginalMissing() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // 원문 필드를 LLM이 누락(9-arg 구 시그니처 = original null) — my_taste만 채운 패치.
        llm.response = new RevisionResult(null, null, null, null, null, null, "산미가 낮았음", null, null);

        Entry entry = reviser(llm).revise(pendingWithSearchOrigin(), "산미는 낮음으로", TODAY)
                .pending().draft().entries().get(0);

        assertThat(entry.myTaste()).isEqualTo("산미가 낮았음");
        assertThat(entry.myTasteOriginal()).isEqualTo("산미가 낮았음"); // 누락 → 정규화본 복사
    }

    @Test
    @DisplayName("V-11: 감상 미변경 패치는 기존 원문을 보존한다 (둘 다 null = 변경 없음)")
    void keepsExistingOriginalWhenTasteUnchanged() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // origin만 바꾸고 감상은 미변경 → my_taste·my_taste_original 둘 다 null 패치.
        llm.response = new RevisionResult(null, null, "콜롬비아", null, null, null, null, null, null);

        Entry entry = reviser(llm).revise(pendingWithSearchOrigin(), "원산지는 콜롬비아야", TODAY)
                .pending().draft().entries().get(0);

        assertThat(entry.myTaste()).isEqualTo("새콤함");         // 기존 정규화본 보존
        assertThat(entry.myTasteOriginal()).isEqualTo("새콤함"); // 기존 원문 보존(stale 아님)
    }

    @Test
    @DisplayName("AC-Δ4: 프롬프트가 고유명사 어미 분리를 지시하고 few-shot 예시를 포함한다 (changes/0013, FR-2)")
    void promptDeclaresProperNounEndingSeparation() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = noChange();

        reviser(llm).revise(pendingWithSearchOrigin(), "로스터리는 카페 화고", TODAY);

        String systemPrompt = llm.captured.systemPrompt();
        // 지시: 고유명사 필드는 조사·연결어미 제거 후 이름만.
        assertThat(systemPrompt).contains("조사").contains("어미");
        // few-shot: 오염 사례("카페 화고" → "카페 화")가 프롬프트에 박혀 있다.
        assertThat(systemPrompt).contains("카페 화고").contains("카페 화");
    }

    // --- changes/0012 TΔ5: edit 모드 — 커피명 거부(V-9)·날짜 이동(AC-39)·record 경로 불변(AC-Δ6) ---

    @Test
    @DisplayName("V-9/AC-38: edit 모드 커피명 변경 패치는 draft에 반영되지 않고 거부로 보고된다")
    void editModeRejectsCoffeeNameChange() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult("커피베라 예가체프 G2", null, null, null, null, null, null, null, null);

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(editPending(), "커피 이름 G2로 바꿔줘", TODAY);

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

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(editPending(), "커피베라 예가체프 G1 맞아", TODAY);

        assertThat(outcome.coffeeNameRejected()).isFalse();
        assertThat(outcome.pending().draft().coffeeName().value()).isEqualTo("커피베라 예가체프 G1");
    }

    @Test
    @DisplayName("AC-39 재료: edit 모드 date 패치는 이번 엔트리의 날짜 이동으로 반영된다(엔트리 개수 불변)")
    void editModeMovesEntryDate() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(null, null, null, null, null, null, null, null, LocalDate.of(2026, 7, 12));

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(editPending(), "이 기록 12일로 옮겨줘", TODAY);

        List<Entry> entries = outcome.pending().draft().entries();
        assertThat(entries).hasSize(1); // 이동이지 복제가 아니다
        assertThat(entries.get(0).date()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(entries.get(0).myTaste()).isEqualTo("새콤함"); // 날짜만 이동 — 나머지 필드 보존
    }

    @Test
    @DisplayName("회귀 가드: record 모드 커피명 패치는 종전대로 반영되고 거부되지 않는다(V-9는 edit 한정)")
    void recordModeAppliesCoffeeNameWithoutRejection() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(
                "커피베라 예가체프 G2", null, null, null, null, null, null, null, null);

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(pendingWithSearchOrigin(), "이름은 G2야", TODAY);

        assertThat(outcome.coffeeNameRejected()).isFalse(); // 거부는 edit 모드 한정(V-9)
        assertThat(outcome.pending().draft().coffeeName().value()).isEqualTo("커피베라 예가체프 G2"); // 종전대로 반영
        assertThat(outcome.recordDatePatch()).isNull(); // date 패치 없음 → 재판정 재료도 없음
    }

    // --- changes/0016 TΔ9: record 모드 date 반영 + today 주입(ADR-39, FR-5/AC-56) ---

    @Test
    @DisplayName("AC-56: record 모드 date 패치가 엔트리 날짜에 반영되고 재판정 재료(recordDatePatch)로 실린다")
    void recordModeAppliesDatePatchAndCarriesMaterial() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // "엊그제 마신 거였어" → LLM이 today(7/14) 기준 12일로 해석한 date 패치.
        llm.response = new RevisionResult(null, null, null, null, null, null, null, null, LocalDate.of(2026, 7, 12));

        PendingReviser.ReviseOutcome outcome =
                reviser(llm).revise(pendingWithSearchOrigin(), "엊그제 마신 거였어", TODAY);

        List<Entry> entries = outcome.pending().draft().entries();
        assertThat(entries).hasSize(1); // 이동이지 복제가 아니다(AC-5)
        assertThat(entries.get(0).date()).isEqualTo(LocalDate.of(2026, 7, 12)); // record 경로 날짜 반영(종전 무시 개정)
        assertThat(outcome.recordDatePatch()).isEqualTo(LocalDate.of(2026, 7, 12)); // 매칭 표기 재판정 재료
    }

    @Test
    @DisplayName("AC-56: 요청에 today가 실려 상대 날짜 해석 기준이 프롬프트에 주어진다")
    void injectsTodayIntoRequest() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = noChange();

        reviser(llm).revise(pendingWithSearchOrigin(), "엊그제 마신 거였어", TODAY);

        String prompt = llm.captured.userPrompt();
        assertThat(prompt).contains("today").contains("2026-07-14"); // 상대 날짜 해석 기준
        assertThat(llm.captured.systemPrompt()).contains("today"); // 지침이 today 기준 계산을 명시
    }

    @Test
    @DisplayName("edit 모드 date 패치는 recordDatePatch를 싣지 않는다 — 날짜 이동은 dateConflict(V-10)로 별도 처리")
    void editModeDoesNotCarryRecordDatePatch() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new RevisionResult(null, null, null, null, null, null, null, null, LocalDate.of(2026, 7, 12));

        PendingReviser.ReviseOutcome outcome = reviser(llm).revise(editPending(), "이 기록 12일로 옮겨줘", TODAY);

        assertThat(outcome.pending().draft().entries().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 12)); // 이동 반영
        assertThat(outcome.recordDatePatch()).isNull(); // edit 모드는 재판정 재료 미적재
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
