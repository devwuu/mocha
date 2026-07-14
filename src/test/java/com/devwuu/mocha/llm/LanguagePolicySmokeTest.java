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
 * 실 OpenAI 호출 스모크(수동, 비용 발생) — 언어 정책(TΔ11, ADR-38, spec FR-2/AC-57)이 실제 추출에서
 * 지켜지는지 원 응답으로 관측한다. 기본 test에서 제외(@Tag("openai")), {@code ./gradlew openaiTest}로만 실행.
 * (ref: CLAUDE.md §5.2 실 API 스모크는 수동, §5.3 비결정적 출력은 단언 아닌 관측)
 * <p>단언하지 않는다 — 모델 출력이라 판정은 로그로 한다. AC-Δ8 기대: 원두 봉투에서 읽은 영문 고유명사
 * (coffee_name·roastery)는 원문 그대로("Ethiopia Chelbesa"), 그 외 텍스트 필드는 한국어 통일
 * (process "washed" → "워시드", roast_level "medium" → "미디엄", origin은 한국어 지명).
 */
@Tag("openai")
class LanguagePolicySmokeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 14);

    @Test
    void printsExtractedFieldsUnderLanguagePolicy() throws Exception {
        String apiKey = resolveApiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 미설정 — 스모크 스킵");

        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        NoteExtractor extractor = new NoteExtractor(
                new OpenAiLlmClient(client, "gpt-4o", 1, MochaObjectMapper.create()),
                MochaObjectMapper.create());

        // AC-Δ8 사례 — 원두 봉투를 읽어 옮긴 발화. 영문 고유명사 + 영문 가공/로스팅 용어 + 영문 원산지가 섞였다.
        String input = "FroB Coffee의 Ethiopia Chelbesa 마셨는데 washed 가공에 medium roast, Gedeb 산이래. 산미 좋고 맛있더라";
        ExtractionResult result = extractor.extract(input, TODAY, List.of());

        System.out.println("=== LANGUAGE POLICY SMOKE (AC-Δ8) ===");
        System.out.println("입력        = " + input);
        System.out.println("coffee_name = " + result.coffeeName() + "   (기대: Ethiopia Chelbesa — 원문 유지)");
        System.out.println("roastery    = " + result.roastery() + "   (기대: FroB Coffee — 원문 유지)");
        System.out.println("origin      = " + result.origin() + "   (기대: 한국어 지명, 예: 게뎁)");
        System.out.println("process     = " + result.process() + "   (기대: 워시드)");
        System.out.println("roast_level = " + result.roastLevel() + "   (기대: 미디엄)");
        System.out.println("my_taste    = " + result.myTaste());
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
