package com.devwuu.mocha.eval;

import com.devwuu.mocha.domain.PendingNote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TΔ1(changes/0026): eval 케이스 로더 — 합성 샘플 소비 + 스키마 위반의 사유 있는 실패(AC-Δ1).
 *
 * <p>이 테스트가 {@code src/test/resources/eval/sample-case}의 <b>소비자</b>다. 실케이스는 비커밋이라
 * 커밋 영역에 남는 유일한 케이스가 그 샘플인데, 소비자가 없으면 포맷과 함께 조용히 썩는다(ADR-68).
 *
 * <p>실 API는 부르지 않는다 — 로더는 파일만 읽는다(AC-Δ1 "기본 test 태스크에서 API 콜 0").
 */
class EvalCaseLoaderTest {

    @Test
    @DisplayName("AC-Δ1: 합성 샘플 케이스가 파싱·검증을 통과하고 필드가 그대로 실린다")
    void loadsSampleCase() {
        EvalCase c = EvalCaseLoader.load(sampleCaseDir());

        // id는 폴더명이 소유한다(case.yaml에 없다).
        assertThat(c.id()).isEqualTo("sample-case");
        assertThat(c.dir()).isEqualTo(sampleCaseDir());
        assertThat(c.origin()).contains("합성 샘플");
        // 고정 시각 — 원 오프셋(+09:00)이 UTC로 정규화되지 않아야 케이스가 의도한 로컬 날짜가 유지된다.
        assertThat(c.today()).isEqualTo(OffsetDateTime.parse("2026-03-14T09:30:00+09:00"));
        assertThat(c.utterances()).hasSize(1);
        assertThat(c.utterances().getFirst()).contains("코스믹베리");
        assertThat(c.initial().notes()).isEqualTo("notes");
        assertThat(c.initial().pending()).isNull();

        EvalCase.Expect expect = c.expect();
        assertThat(expect.pending().state()).isEqualTo(EvalCase.Pending.State.CREATED);
        assertThat(expect.pending().mode()).isEqualTo(PendingNote.Mode.RECORD);
        assertThat(expect.pending().draft()).hasSize(4);
        assertThat(expect.pending().draft().getFirst().path()).isEqualTo("entries[0].date");
        assertThat(expect.pending().draft().getFirst().value()).isEqualTo("2026-03-14");
        assertThat(expect.pending().draft().get(1).size()).isEqualTo(1);
        assertThat(expect.pending().draft().getLast().present()).isTrue();
        assertThat(expect.tools().sequenceContains()).containsExactly("propose_record");
        assertThat(expect.tools().calls()).hasSize(1);
        assertThat(expect.tools().calls().getFirst().name()).isEqualTo("propose_record");
        assertThat(expect.tools().calls().getFirst().min()).isEqualTo(1);
        assertThat(expect.rejections().max()).isZero();
        assertThat(expect.unchangedNotes()).isTrue();
    }

    @Test
    @DisplayName("AC-Δ1: 샘플 케이스의 draft 경로가 전부 파싱된다 — 케이스 파일과 경로 문법이 함께 썩지 않는다")
    void sampleCaseDraftPathsParse() {
        EvalCase c = EvalCaseLoader.load(sampleCaseDir());

        assertThat(c.expect().pending().draft())
                .allSatisfy(a -> assertThat(a.parsedPath().segments()).isNotEmpty());
        assertThat(c.expect().pending().draft().get(2).parsedPath().segments())
                .containsExactly(
                        new EvalPath.Segment.Field("entries"),
                        new EvalPath.Segment.Index(0),
                        new EvalPath.Segment.Field("brews"),
                        new EvalPath.Segment.Index(0),
                        new EvalPath.Segment.Field("recipe"),
                        new EvalPath.Segment.Field("grind"));
    }

    @Test
    @DisplayName("AC-Δ1: 샘플 케이스가 참조한 노트 픽스처가 실재한다 — 초기 상태가 조용히 비지 않는다")
    void sampleCaseNotesFixtureExists() throws IOException {
        Path notes = sampleCaseDir().resolve("notes");

        try (Stream<Path> files = Files.list(notes)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .isNotEmpty()
                    .allSatisfy(name -> assertThat(name).endsWith(".json"));
        }
    }

    @Test
    @DisplayName("AC-Δ1: 케이스 디렉터리 스캔은 폴더명을 id로 삼아 오름차순으로 로드한다")
    void loadsAllCasesSortedByFolderName() throws IOException {
        Path casesDir = Files.createTempDirectory("eval-cases");
        copyCase(sampleCaseDir(), casesDir.resolve("b-second"));
        copyCase(sampleCaseDir(), casesDir.resolve("a-first"));
        // 숨김 폴더는 케이스가 아니다.
        Files.createDirectories(casesDir.resolve(".hidden"));

        List<EvalCase> cases = EvalCaseLoader.loadAll(casesDir);

        assertThat(cases).extracting(EvalCase::id).containsExactly("a-first", "b-second");
    }

    @Test
    @DisplayName("AC-Δ2: 케이스 디렉터리 부재·빈 디렉터리는 실패가 아니라 빈 목록 — 클론 직후 환경 안전")
    void missingOrEmptyCasesDirYieldsEmptyList(@TempDir Path tmp) {
        assertThat(EvalCaseLoader.loadAll(tmp.resolve("없는-디렉터리"))).isEmpty();
        assertThat(EvalCaseLoader.loadAll(tmp)).isEmpty();
        assertThat(EvalCaseLoader.loadAll(null)).isEmpty();
    }

    @Test
    @DisplayName("AC-Δ1: case.yaml이 없는 폴더는 사유와 함께 실패한다")
    void rejectsFolderWithoutCaseFile(@TempDir Path tmp) throws IOException {
        Path caseDir = Files.createDirectories(tmp.resolve("no-yaml"));

        assertThatThrownBy(() -> EvalCaseLoader.load(caseDir))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("no-yaml")
                .hasMessageContaining("case.yaml이 없다");
    }

    @Test
    @DisplayName("AC-Δ1: 모르는 필드는 실패한다 — 오타 난 기대가 '지정 안 함'으로 흘러 초록 거짓말이 되는 것을 막는다")
    void rejectsUnknownField(@TempDir Path tmp) {
        String yaml = """
                origin: "오타 케이스"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  pendign:            # pending 오타
                    state: created
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "typo", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("typo/case.yaml")
                .hasMessageContaining("파싱 실패");
    }

    @Test
    @DisplayName("AC-Δ1: id·dir을 case.yaml에 쓰면 실패한다 — 신원은 폴더명이 소유한다")
    void rejectsIdInCaseFile(@TempDir Path tmp) {
        String yaml = """
                id: "다른-이름"
                origin: "신원 이중화"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  unchanged_notes: true
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "identity", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("폴더명이 케이스 id를 소유한다");
    }

    @Test
    @DisplayName("AC-Δ1: origin 누락은 사유와 함께 실패한다 — 케이스가 어느 관측에서 왔는지가 유일한 존재 근거")
    void rejectsMissingOrigin(@TempDir Path tmp) {
        String yaml = """
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  unchanged_notes: true
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "no-origin", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("no-origin/case.yaml: origin")
                .hasMessageContaining("필수 필드가 비었다");
    }

    @Test
    @DisplayName("AC-Δ1: today 누락은 사유와 함께 실패한다 — 고정 시각 없이는 재현이 성립하지 않는다")
    void rejectsMissingToday(@TempDir Path tmp) {
        String yaml = """
                origin: "시계 없음"
                utterances: ["안녕"]
                expect:
                  unchanged_notes: true
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "no-today", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("today")
                .hasMessageContaining("2026-07-18T09:00:00+09:00");
    }

    @Test
    @DisplayName("AC-Δ1: 빈 발화 시퀀스는 실패한다")
    void rejectsEmptyUtterances(@TempDir Path tmp) {
        String yaml = """
                origin: "발화 없음"
                today: "2026-03-14T09:30:00+09:00"
                utterances: []
                expect:
                  unchanged_notes: true
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "no-utterance", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("utterances")
                .hasMessageContaining("최소 1개");
    }

    @Test
    @DisplayName("AC-Δ1: 케이스 폴더 밖 픽스처 참조는 실패한다")
    void rejectsFixtureOutsideCaseFolder(@TempDir Path tmp) {
        String yaml = """
                origin: "폴더 밖 참조"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                initial:
                  notes: "../../data/notes"
                expect:
                  unchanged_notes: true
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "escape", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("initial.notes")
                .hasMessageContaining("케이스 폴더 밖");
    }

    @Test
    @DisplayName("AC-Δ1: 실재하지 않는 픽스처 참조는 실패한다 — 초기 상태가 조용히 비는 것을 막는다")
    void rejectsMissingFixture(@TempDir Path tmp) {
        String yaml = """
                origin: "픽스처 없음"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                initial:
                  pending: pending.json
                expect:
                  unchanged_notes: true
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "no-fixture", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("initial.pending")
                .hasMessageContaining("파일가 없다: pending.json");
    }

    @Test
    @DisplayName("AC-Δ1: 아무것도 단언하지 않는 기대는 실패한다 — 비용만 쓰고 통과하는 케이스 차단")
    void rejectsEmptyExpect(@TempDir Path tmp) {
        String yaml = """
                origin: "빈 기대"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect: {}
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "empty-expect", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("expect")
                .hasMessageContaining("기대가 하나도 없다");
    }

    @Test
    @DisplayName("AC-Δ1: state=absent인데 draft를 단언하면 실패한다 — 모순된 기대")
    void rejectsAbsentPendingWithDraftAssertions(@TempDir Path tmp) {
        String yaml = """
                origin: "모순"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  pending:
                    state: absent
                    draft:
                      - path: entries[0].date
                        value: "2026-03-14"
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "contradiction", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("state=absent")
                .hasMessageContaining("draft 단언");
    }

    @Test
    @DisplayName("AC-Δ1: 경로 단언이 value·size·present를 겹쳐 쓰면 실패한다 — 무엇이 깨졌는지 흐려진다")
    void rejectsAmbiguousAssertion(@TempDir Path tmp) {
        String yaml = """
                origin: "겹친 단언"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  pending:
                    state: created
                    draft:
                      - path: entries[0].brews
                        size: 2
                        present: true
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "ambiguous", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("expect.pending.draft[0]")
                .hasMessageContaining("정확히 1개")
                .hasMessageContaining("size·present");
    }

    @Test
    @DisplayName("AC-Δ1: 단언이 하나도 없는 경로 항목은 실패한다")
    void rejectsAssertionWithoutPredicate(@TempDir Path tmp) {
        String yaml = """
                origin: "빈 단언"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  pending:
                    state: created
                    draft:
                      - path: entries[0].date
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "no-predicate", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("정확히 1개")
                .hasMessageContaining("없음");
    }

    @Test
    @DisplayName("AC-Δ1: 경로 문법 오류는 어느 케이스의 어느 단언인지와 함께 실패한다")
    void rejectsMalformedPath(@TempDir Path tmp) {
        String yaml = """
                origin: "경로 오타"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  pending:
                    state: created
                    draft:
                      - path: "entries[0]..grind"
                        present: true
                """;

        assertThatThrownBy(() -> loadYaml(tmp, "bad-path", yaml))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("bad-path/case.yaml: expect.pending.draft[0].path")
                .hasMessageContaining("경로 문법 오류");
    }

    @Test
    @DisplayName("AC-Δ1: tool 호출 기대의 이름 누락·빈 기대는 실패한다")
    void rejectsIncompleteCallExpectation(@TempDir Path tmp) {
        String noName = """
                origin: "이름 없음"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  tools:
                    calls:
                      - min: 1
                """;
        assertThatThrownBy(() -> loadYaml(tmp, "call-no-name", noName))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("expect.tools.calls[0].name");

        String noPredicate = """
                origin: "기대 없음"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  tools:
                    calls:
                      - name: propose_record
                """;
        assertThatThrownBy(() -> loadYaml(tmp, "call-no-predicate", noPredicate))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("min·max·args 중 최소 1종");
    }

    @Test
    @DisplayName("AC-Δ1: 뒤집힌 범위(min > max)·음수 횟수는 실패한다")
    void rejectsInvalidCounts(@TempDir Path tmp) {
        String inverted = """
                origin: "뒤집힌 범위"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  tools:
                    total:
                      min: 3
                      max: 1
                """;
        assertThatThrownBy(() -> loadYaml(tmp, "inverted", inverted))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("expect.tools.total")
                .hasMessageContaining("min(3)이 max(1)보다 크다");

        String negative = """
                origin: "음수"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["안녕"]
                expect:
                  rejections:
                    max: -1
                """;
        assertThatThrownBy(() -> loadYaml(tmp, "negative", negative))
                .isInstanceOf(EvalCaseFormatException.class)
                .hasMessageContaining("expect.rejections.max")
                .hasMessageContaining("음수");
    }

    @Test
    @DisplayName("AC-Δ1: 초기 pending을 동봉한 케이스가 로드된다 — 단일 대기 게이트 유형의 초기 상태")
    void loadsCaseWithInitialPending(@TempDir Path tmp) throws IOException {
        Path caseDir = Files.createDirectories(tmp.resolve("with-pending"));
        Files.writeString(caseDir.resolve("pending.json"), "{}", StandardCharsets.UTF_8);
        String yaml = """
                origin: "단일 대기 게이트 유형"
                today: "2026-03-14T09:30:00+09:00"
                utterances: ["다른 커피 기록해줘"]
                initial:
                  pending: pending.json
                expect:
                  pending:
                    state: unchanged
                  rejections:
                    min: 1
                """;
        Files.writeString(caseDir.resolve("case.yaml"), yaml, StandardCharsets.UTF_8);

        EvalCase c = EvalCaseLoader.load(caseDir);

        assertThat(c.initial().pending()).isEqualTo("pending.json");
        assertThat(c.expect().pending().state()).isEqualTo(EvalCase.Pending.State.UNCHANGED);
        assertThat(c.expect().rejections().min()).isEqualTo(1);
    }

    // --- 헬퍼 ---

    private static Path sampleCaseDir() {
        try {
            return Path.of(EvalCaseLoaderTest.class.getResource("/eval/sample-case").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("샘플 케이스 리소스를 찾을 수 없다", e);
        }
    }

    private static EvalCase loadYaml(Path parent, String id, String yaml) {
        try {
            Path caseDir = Files.createDirectories(parent.resolve(id));
            Files.writeString(caseDir.resolve("case.yaml"), yaml, StandardCharsets.UTF_8);
            return EvalCaseLoader.load(caseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyCase(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
