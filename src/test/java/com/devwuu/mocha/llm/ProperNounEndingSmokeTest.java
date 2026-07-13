package com.devwuu.mocha.llm;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.pipeline.ExtractionResult;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실 OpenAI 호출 스모크(수동, 비용 발생) — 고유명사 어미 분리 프롬프트(TΔ5, FR-2/AC-Δ4)가 실제 추출에서
 * 조사·연결어미를 떼어내는지 원 응답으로 관측한다. 기본 test에서 제외(@Tag("openai")),
 * {@code ./gradlew openaiTest}로만 실행. (ref: CLAUDE.md §5.2 실 API 스모크는 수동, §5.3 비결정적 출력은
 * 단언 아닌 관측)
 * <p>단언하지 않는다 — 모델 출력이라 판정은 로그로 한다. AC-Δ4 기대: roastery가 "카페 화"(❌ "카페 화고").
 */
@Tag("openai")
class ProperNounEndingSmokeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 13);

    @Test
    void printsExtractedRoasteryEndingStripped() throws Exception {
        String apiKey = resolveApiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 미설정 — 스모크 스킵");

        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        NoteExtractor extractor = new NoteExtractor(
                new OpenAiLlmClient(client, "gpt-4o", 1, MochaObjectMapper.create()),
                MochaObjectMapper.create());

        // AC-Δ4의 오염 사례 — "카페 화고"의 연결어미 '고'가 붙은 발화.
        ExtractionResult result = extractor.extract(
                "로스터리는 카페 화고 달고 맛있더라", TODAY, List.of());

        System.out.println("=== PROPER-NOUN ENDING SMOKE (AC-Δ4) ===");
        System.out.println("입력     = 로스터리는 카페 화고 달고 맛있더라");
        System.out.println("roastery = " + result.roastery() + "   (기대: 카페 화, ❌ 카페 화고)");
        System.out.println("coffee   = " + result.coffeeName());
        System.out.println("my_taste = " + result.myTaste());
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
