package com.devwuu.mocha.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ2(changes/0002-instagram-share-card): {@link CardImageRenderer} 경계 + Playwright 구현 검증.
 * <p>두 갈래로 나눈다:
 * <ul>
 *   <li>{@link Deterministic} — 실 Chromium 없이 인자 검증·경계 계약(fake). 기본 {@code ./gradlew test}에서 실행.
 *   <li>{@link RealRasterization} — 실 헤드리스 Chromium 래스터화. {@code @Tag("chromium")}로 분리해
 *       기본 실행 제외, {@code ./gradlew chromiumTest}로만(백엔드 CLAUDE.md §5.2).
 * </ul>
 */
class PlaywrightCardImageRendererTest {

    /** 실 Chromium 없이 도는 결정론적 계약 검증. */
    @Nested
    class Deterministic {

        private final CardImageRenderer renderer = new PlaywrightCardImageRenderer(1080, 1350, 0.9);

        @Test
        @DisplayName("TΔ2: 빈 HTML은 래스터화 전에 거부한다")
        void rejectsBlankHtml(@TempDir Path work) {
            Path out = work.resolve("cards/x/2026-07-11.jpg");
            assertThrows(IllegalArgumentException.class, () -> renderer.render(null, work, out));
            assertThrows(IllegalArgumentException.class, () -> renderer.render("  ", work, out));
            // 거부는 Chromium 기동·파일 생성 전에 일어난다 — 산출물이 남지 않는다.
            assertFalse(Files.exists(out), "거부 시 출력 JPG가 생성되지 않아야 한다");
        }

        @Test
        @DisplayName("TΔ2: baseDir·out은 필수다")
        void rejectsNullPaths(@TempDir Path work) {
            assertThrows(IllegalArgumentException.class,
                    () -> renderer.render("<html></html>", null, work.resolve("o.jpg")));
            assertThrows(IllegalArgumentException.class,
                    () -> renderer.render("<html></html>", work, null));
        }

        @Test
        @DisplayName("TΔ2: fake 더블이 (html, baseDir, out) 계약을 캡처하고 유효 JPEG 스텁을 쓴다")
        void fakeCapturesBoundaryContract(@TempDir Path work) throws Exception {
            FakeCardImageRenderer fake = new FakeCardImageRenderer();
            Path baseDir = work.resolve("artifact");
            Path out = baseDir.resolve("cards/ethiopia-yirg/2026-07-11.jpg");

            fake.render("<html>entry</html>", baseDir, out);

            assertEquals(1, fake.calls.size(), "render 호출이 1회 기록되어야 한다");
            FakeCardImageRenderer.Call call = fake.calls.get(0);
            assertEquals("<html>entry</html>", call.html());
            assertEquals(baseDir, call.baseDir());
            assertEquals(out, call.out());
            // 다운스트림(TΔ4/TΔ5)의 "카드 JPG 존재" 단언이 결정론적으로 돌게 스텁을 쓴다.
            assertTrue(Files.exists(out), "fake는 out에 JPEG 스텁을 써야 한다");
            byte[] bytes = Files.readAllBytes(out);
            assertTrue(bytes.length >= 3
                            && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF,
                    "스텁은 유효 JPEG 시그니처(FF D8 FF)를 가져야 한다");
        }

        @Test
        @DisplayName("TΔ2: fake는 실패 주입 시 예외를 던진다 (실패 격리 경로용, AC-Δ6)")
        void fakeCanInjectFailure(@TempDir Path work) {
            FakeCardImageRenderer fake = new FakeCardImageRenderer();
            fake.failOnRender = true;
            assertThrows(RuntimeException.class,
                    () -> fake.render("<html></html>", work, work.resolve("o.jpg")));
        }
    }

    /** 실 헤드리스 Chromium을 기동하는 무거운 래스터화 — 태그 분리(기본 test 제외). */
    @Nested
    @Tag("chromium")
    class RealRasterization {

        private static final String BUNDLED_FONT = "/assets/fonts/GowunDodum-Regular.ttf";
        private static final int CARD_W = 1080;
        private static final int CARD_H = 1350; // 4:5

        @Test
        @DisplayName("TΔ2: 카드 HTML을 1080×1350 유효 JPG로 굽고 baseDir의 로컬 폰트를 해석한다 (AC-Δ2/AC-Δ3)")
        void rendersCardToFourFiveJpegWithLocalFont(@TempDir Path baseDir) throws Exception {
            // 폰트 미번들이면 하드 실패 대신 skip — 온디맨드 테스트라 준비 전 실행을 관대히(assets/fonts/README.md).
            byte[] font;
            try (InputStream in = getClass().getResourceAsStream(BUNDLED_FONT)) {
                Assumptions.assumeTrue(in != null,
                        "폰트 미번들: src/main/resources" + BUNDLED_FONT + " — assets/fonts/README.md대로 받은 뒤 실행");
                font = in.readAllBytes();
            }
            // 폰트를 baseDir에 두고 카드 HTML은 상대 경로로 참조 → base 경로 처리로 로컬 폰트가 해석되어야 한다.
            Files.write(baseDir.resolve("GowunDodum-Regular.ttf"), font);
            String html = """
                    <!doctype html><html lang="ko"><head><meta charset="utf-8">
                    <style>
                      @font-face{font-family:'Gowun Dodum';src:url('GowunDodum-Regular.ttf') format('truetype');}
                      html,body{margin:0;padding:0}
                      body{width:%dpx;height:%dpx;background:#efe9e2;
                           font-family:'Gowun Dodum',sans-serif;
                           display:flex;align-items:center;justify-content:center;font-size:56px;color:#3a2a1a}
                    </style></head>
                    <body>모카 엔트리 카드 ☕</body></html>
                    """.formatted(CARD_W, CARD_H);

            Path out = baseDir.resolve("cards/ethiopia-yirg/2026-07-11.jpg");
            CardImageRenderer renderer = new PlaywrightCardImageRenderer(CARD_W, CARD_H, 0.9);
            renderer.render(html, baseDir, out);

            assertTrue(Files.exists(out), "카드 JPG가 생성되어야 한다");
            byte[] bytes = Files.readAllBytes(out);
            assertTrue(bytes.length > 3
                            && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF,
                    "유효한 JPEG 시그니처여야 한다");
            BufferedImage img = ImageIO.read(out.toFile());
            assertEquals(CARD_W, img.getWidth(), "카드 폭 = 1080 (4:5)");
            assertEquals(CARD_H, img.getHeight(), "카드 높이 = 1350 (4:5)");
            // 중간 입력 HTML은 남지 않는다(ADR-10) — baseDir에 남은 card-*.html 임시 파일이 없어야 한다.
            try (var s = Files.list(baseDir)) {
                assertFalse(s.anyMatch(p -> p.getFileName().toString().startsWith("card-")),
                        "임시 카드 HTML은 렌더 후 삭제되어야 한다");
            }
        }
    }
}
