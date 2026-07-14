package com.devwuu.mocha.llm;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.pipeline.ContextHint;
import com.devwuu.mocha.pipeline.IntentClassifier;
import com.devwuu.mocha.pipeline.IntentResult;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실 OpenAI 호출 스모크(수동, 비용 발생) — 게이트 판정 기준 재정의(ADR-35, FR-17/AC-50)가 버그 리포트 사례에서
 * 의도대로 작동하는지 실 판정으로 관측한다. 기본 test에서 제외(@Tag("openai")), {@code ./gradlew openaiTest}로만
 * 실행. (ref: CLAUDE.md §5.2 실 API 스모크는 수동, §5.3 비결정적 출력은 단언 아닌 관측)
 * <p>단언하지 않는다 — 모델 판정이라 결론은 로그로 한다. 관측 배경: 2026-07-14 실사용에서 "오늘 먹은 에티오피아
 * 첼베사 있잖아"(참조 화법 + 새 감상 없음)가 record로 오분류돼 갱신 미리보기가 떴다(사용자 [취소] 2회). 새 기준은
 * "새 시음 감상 유무"로, 무감상 참조 화법은 search로 가야 한다(AC-50). 대조군으로 새 감상이 담긴 문장은 record로
 * 유지되는지 함께 관측한다(기록 유실 방지 폴백 불변).
 */
@Tag("openai")
class IntentGateSmokeTest {

    private static final ContextHint NO_CONTEXT = new ContextHint(false, false);

    @Test
    void printsGateVerdictsForReportCases() throws Exception {
        String apiKey = resolveApiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 미설정 — 스모크 스킵");

        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        IntentClassifier classifier = new IntentClassifier(
                new OpenAiLlmClient(client, "gpt-4o", 1, MochaObjectMapper.create()),
                MochaObjectMapper.create());

        // ① 버그 리포트 사례 — 참조 화법 + 새 감상 없음. 기대: search (AC-50). 종전엔 record로 새어 갱신 미리보기가 떴다.
        String referenceOnly = "오늘 먹은 에티오피아 첼베사 있잖아";
        // ② 대조군 — 같은 커피지만 이번 시음의 새 감상이 담김. 기대: record (기록 유실 방지 폴백 불변).
        String newImpression = "오늘 에티오피아 첼베사 마셨는데 새콤하고 깔끔해서 좋았어";

        IntentResult r1 = classifier.classify(referenceOnly, NO_CONTEXT);
        IntentResult r2 = classifier.classify(newImpression, NO_CONTEXT);

        System.out.println("=== INTENT GATE SMOKE (AC-50, ADR-35) ===");
        System.out.println("① 참조 화법+무감상 : \"" + referenceOnly + "\"");
        System.out.println("   → intent = " + r1.intent() + "   (기대: search)");
        System.out.println("② 새 감상 있음     : \"" + newImpression + "\"");
        System.out.println("   → intent = " + r2.intent() + "   (기대: record)");
        System.out.println("=== END ===");
    }

    // .env.local(KEY=VALUE properties)에서 OPENAI_API_KEY를 읽는다. 없으면 환경변수 폴백. 파일 내용은 프로세스만 읽는다.
    private static String resolveApiKey() throws Exception {
        Path envLocal = Path.of(".env.local");
        if (Files.exists(envLocal)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(envLocal)) {
                props.load(in);
            }
            String key = props.getProperty("OPENAI_API_KEY");
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return System.getenv("OPENAI_API_KEY");
    }
}
