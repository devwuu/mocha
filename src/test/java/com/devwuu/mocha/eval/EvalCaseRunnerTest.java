package com.devwuu.mocha.eval;

import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.assertj.core.api.Assertions.fail;

/**
 * TΔ2(changes/0026): 행동 eval 러너 — 실발화 케이스를 실 LLM으로 리플레이하고 기대 계약을 판정한다
 * (ref: plan.md#ADR-68·69, AC-Δ2·Δ3·Δ4).
 *
 * <p>단위 테스트도 스모크도 아닌 <b>세 번째 부류</b>다(백엔드 CLAUDE.md §5.3): 단위는 fake로 계약을
 * 결정론 검증하고, 스모크는 실 API를 관측만 하며(단언 없음), eval은 <b>실 LLM을 돌리면서 단언한다</b>.
 * 비결정성은 문구를 안 보고 구조·계약만 보는 것(ADR-68)과 회차 반복으로 다룬다.
 *
 * <p>실행: {@code ./gradlew evalTest} — 기본 {@code test}는 {@code eval} 태그를 제외한다(비용).
 * <ul>
 *   <li>{@code -Dmocha.eval.cases-dir} — 케이스 루트(기본 {@code eval/cases}, 비커밋 개인 데이터)</li>
 *   <li>{@code -Dmocha.eval.repetitions} — 케이스당 반복 수(기본 3). <b>전 회차 통과해야 그린</b>(AC-Δ3)</li>
 *   <li>{@code -Dmocha.eval.model}·{@code -Dmocha.eval.segmenter-model} — 모델 재측정용 덮어쓰기.
 *       기본값은 프로덕션(application.yaml)과 같다 — 행동 레이어 변경 전/후 비교가 ADR-69 ②의 재측정이다</li>
 * </ul>
 * 이 파라미터들은 테스트 JVM {@code -D} 전용이다 — {@code mocha.*} 런타임 설정 키가 아니다(plan §5 노트).
 *
 * <p>스킵 안전(AC-Δ2): 키가 없거나 케이스 디렉터리가 없거나 비면 <b>스킵</b>으로 끝난다. 케이스는 실발화라
 * 비커밋이고, 클론 직후 환경에는 없는 것이 정상이라 부재가 실패일 수 없다. 반대로 <b>케이스 파일이 있는데
 * 스키마를 위반</b>하면 스킵이 아니라 실패다 — 로더가 사유와 함께 터뜨린다(AC-Δ1).
 */
@Tag("eval")
class EvalCaseRunnerTest {

    private static final String CASES_DIR = "mocha.eval.cases-dir";
    private static final String REPETITIONS = "mocha.eval.repetitions";
    private static final String DEFAULT_CASES_DIR = "eval/cases";
    private static final int DEFAULT_REPETITIONS = 3; // ADR-68 — 3회 전부 통과가 그린(2026-07-27 사용자 확정)

    // 회차마다 새 저장소를 깔 뿌리. 클래스 단위 @TempDir이라 실행이 끝나면 통째로 정리된다.
    @TempDir
    static Path workRoot;

    @TestFactory
    Stream<DynamicTest> replaysEvalCases() {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Stream.of(skip("OPENAI_API_KEY 미설정 — eval 스킵"));
        }
        Path casesDir = Path.of(System.getProperty(CASES_DIR, DEFAULT_CASES_DIR));
        // 스키마 위반은 여기서 사유와 함께 터진다(스킵 아님) — 케이스가 있는데 못 읽는 것은 실패다.
        List<EvalCase> cases = EvalCaseLoader.loadAll(casesDir);
        if (cases.isEmpty()) {
            return Stream.of(skip("케이스 없음(" + casesDir.toAbsolutePath() + ") — eval 스킵. "
                    + "-D" + CASES_DIR + "로 케이스 루트를 지정한다"));
        }

        EvalHarness.Settings settings = new EvalHarness.Settings(apiKey,
                System.getProperty("mocha.eval.model", "gpt-5.4"),               // mocha.agent.model
                System.getProperty("mocha.eval.segmenter-model", "gpt-5.4-mini")); // mocha.agent.segmenter-model
        int repetitions = Integer.getInteger(REPETITIONS, DEFAULT_REPETITIONS);

        return cases.stream()
                .map(evalCase -> dynamicTest(evalCase.id(), () -> replay(evalCase, settings, repetitions)));
    }

    private static DynamicTest skip(String reason) {
        return dynamicTest("eval 스킵", () -> assumeTrue(false, reason));
    }

    private void replay(EvalCase evalCase, EvalHarness.Settings settings, int repetitions) throws IOException {
        ObjectMapper mapper = MochaObjectMapper.create();
        List<String> report = new ArrayList<>();
        int failedRepetitions = 0;

        for (int repetition = 1; repetition <= repetitions; repetition++) {
            Path workDir = Files.createDirectories(
                    workRoot.resolve(evalCase.id()).resolve("rep-" + repetition));
            EvalHarness.Run run = EvalHarness.run(evalCase, workDir, settings);
            List<String> failures = EvalJudge.judge(evalCase, run, mapper);
            if (!failures.isEmpty()) {
                failedRepetitions++;
            }
            report.add(renderRepetition(repetition, repetitions, run, failures));
        }

        // 통과해도 출력한다 — 통과한 회차의 tool 시퀀스·소요가 다음 변경의 비교 기준선이다(ADR-69 ②, TΔ3).
        String header = "=== eval [" + evalCase.id() + "] " + (repetitions - failedRepetitions)
                + "/" + repetitions + " 통과 · model=" + settings.agentModel()
                + " · origin=" + evalCase.origin() + " ===";
        String body = header + "\n" + String.join("\n", report);
        System.out.println(body);

        if (failedRepetitions > 0) {
            // AC-Δ3: 일부만 통과해도 실패다 — 비결정 출력에서 "가끔 맞는다"는 계약 충족이 아니다.
            fail("케이스 '" + evalCase.id() + "' 실패 — " + failedRepetitions + "/" + repetitions
                    + " 회차가 기대 계약을 어겼다.\n" + body);
        }
    }

    // AC-Δ4: 로그 재수집 없이 원인을 볼 수 있게, 회차별 tool 시퀀스·거부 사유·pending diff를 한 덩어리로 남긴다.
    private static String renderRepetition(int repetition, int total, EvalHarness.Run run,
                                           List<String> failures) {
        StringBuilder out = new StringBuilder();
        out.append("--- 회차 ").append(repetition).append('/').append(total)
                .append(failures.isEmpty() ? " : 통과" : " : 실패")
                .append(" (").append(run.elapsedMs()).append("ms)\n");
        out.append("  tool 시퀀스 = ")
                .append(run.tools().stream().map(RecordingToolCallbacks.Invocation::name).toList())
                .append('\n');
        List<RecordingToolCallbacks.Invocation> rejected = run.tools().stream()
                .filter(RecordingToolCallbacks.Invocation::rejected)
                .toList();
        out.append("  검증 거부   = ").append(rejected.size()).append('\n');
        for (RecordingToolCallbacks.Invocation invocation : rejected) {
            out.append("    · ").append(invocation.name()).append(": ")
                    .append(invocation.rejectionReason()).append('\n');
        }
        List<Integer> fallbacks = run.fallbackTurns();
        if (!fallbacks.isEmpty()) {
            // 폴백은 예외를 삼킨 턴이다(ADR-48) — 사유는 logs/의 warn 스택에 있다.
            out.append("  턴 폴백     = 턴 인덱스 ").append(fallbacks)
                    .append(" (사유는 logs/ 폴백 warn 스택)\n");
        }
        if (failures.isEmpty()) {
            return out.toString().stripTrailing();
        }
        out.append("  위반:\n");
        failures.forEach(failure -> out.append("    - ").append(failure).append('\n'));
        out.append("  pending before = ").append(run.pendingBefore().orElse("없음")).append('\n');
        out.append("  pending after  = ").append(run.pendingAfter().orElse("없음")).append('\n');
        out.append("  tool 인자:\n");
        for (RecordingToolCallbacks.Invocation invocation : run.tools()) {
            out.append("    · ").append(invocation.name()).append(" <- ")
                    .append(invocation.argumentsJson()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    // .env.local(KEY=VALUE properties) → 환경변수 폴백. 스모크와 같은 조달 경로(파일 내용은 프로세스만 읽는다).
    private static String resolveApiKey() {
        Path envLocal = Path.of(".env.local");
        if (Files.exists(envLocal)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(envLocal)) {
                props.load(in);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("`.env.local` 읽기 실패", e);
            }
            String key = props.getProperty("OPENAI_API_KEY");
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return System.getenv("OPENAI_API_KEY");
    }
}
