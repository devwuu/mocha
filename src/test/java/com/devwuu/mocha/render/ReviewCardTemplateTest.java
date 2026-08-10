package com.devwuu.mocha.render;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Rating;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ4a(changes/0021): 감상 카드 템플릿({@code <theme>/review.html}) 이식 계약 검증 — 실 래스터화 없이
 * Thymeleaf 산출 HTML로 FR-7 필드↔영역 매핑·확정 편차·null 분기를 결정론적으로 본다(백엔드 CLAUDE.md §5.2).
 * 실 Chromium 스모크(비주얼·오프라인 렌더)는 {@link ReviewCardChromiumSmokeTest}(태그 분리).
 * <ul>
 *   <li>AC-64: beans 원두별 설명+가공방식 병기</li>
 *   <li>AC-Δ5: FR-7 매핑 렌더·rating 뱃지 이모티콘 없음</li>
 *   <li>AC-Δ9: CDN·외부 URL 참조 없음(로컬 번들 폰트 참조)</li>
 * </ul>
 */
class ReviewCardTemplateTest {

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 구 템플릿(note.html·RatingStyle.emoji)이 쓰던 장식 이모지 전수 — 이식 편차①로 감상 카드에서 제거됐다.
    private static final List<String> BANNED_EMOJIS = List.of("☕", "🌍", "💧", "🔥", "🏷", "🐾", "🔗", "❤", "😋", "🙂", "😓");

    private static NoteView.ReviewCard blendCard(Rating rating) {
        return new NoteView.ReviewCard(
                "레인보우 블렌드",
                "커피가게 동경",
                List.of(
                        new NoteView.BeanLine("에티오피아 예가체프 헤어룸", "워시드"),
                        new NoteView.BeanLine("콜롬비아 우일라 카투라", "내추럴"),
                        new NoteView.BeanLine("브라질 세하도 버번", null)),
                "라이트",
                List.of("자몽", "베르가못", "홍차"),
                LocalDate.parse("2026-07-18"),
                "새콤하고 좋았음. 식으니까 자몽 같은 신맛이 올라옴.",
                rating);
    }

    private String render(Theme theme, NoteView.ReviewCard card) {
        Context ctx = new Context(Locale.KOREAN);
        // TΔ5a 렌더러 baseContext와 같은 컨텍스트 계약 — fmt/rs/amt 헬퍼 + 마스코트 상대 경로.
        ctx.setVariable("fmt", new KoreanDates());
        ctx.setVariable("rs", new RatingStyle());
        ctx.setVariable("amt", new RecipeAmounts());
        ctx.setVariable("mascotHref", "mascot-face.png");
        ctx.setVariable("card", card);
        return engine.process(theme.id() + "/review", ctx);
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-64/AC-Δ5: 두 테마 감상 카드가 FR-7 매핑(beans 원두별 병기·로스팅·노트·감상·rating 뱃지)을 렌더한다")
    void rendersFieldAreaMappingForBothThemes(Theme theme) {
        String html = render(theme, blendCard(Rating.GOOD));

        // ②: 커피명·로스터리
        assertTrue(html.contains("레인보우 블렌드"), "커피명");
        assertTrue(html.contains("커피가게 동경"), "로스터리");
        // ③: beans 원두별 설명+가공방식 병기(AC-64) — process 없는 원두는 설명만
        assertTrue(html.contains("에티오피아 예가체프 헤어룸"), "원두1 설명");
        assertTrue(html.contains("워시드"), "원두1 가공방식");
        assertTrue(html.contains("콜롬비아 우일라 카투라"), "원두2 설명");
        assertTrue(html.contains("내추럴"), "원두2 가공방식");
        assertTrue(html.contains("브라질 세하도 버번"), "원두3 설명(가공방식 없음)");
        // ④⑤: roast_level·official_notes
        assertTrue(html.contains("라이트"), "로스팅");
        assertTrue(html.contains("자몽") && html.contains("베르가못") && html.contains("홍차"), "official_notes 태그");
        // ⑥: my_taste(autofit 대상) + rating 뱃지
        assertTrue(html.contains("새콤하고 좋았음."), "감상 본문");
        assertTrue(html.contains("data-autofit=\"10.5\""), "감상 영역 autofit(하한 = 시안 값)");
        assertTrue(html.contains("맛있다"), "rating 뱃지 라벨");
        // ①: 날짜 헤더(테마별 포맷)
        assertTrue(theme == Theme.TYPE_A ? html.contains("2026. 7. 18") : html.contains("2026년 7월 18일의 커피"),
                "날짜 헤더");
        // 시안 예시 데이터가 남지 않았다(편차③ — Thymeleaf 바인딩 대체)
        assertFalse(html.contains("봄맞이 블렌드"), "시안 예시 데이터 잔존 없음");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ9: 감상 카드 HTML은 외부 URL을 참조하지 않는다 — 로컬 번들 폰트만(ADR-11)")
    void referencesNoExternalUrls(Theme theme) {
        String html = render(theme, blendCard(Rating.GOOD));
        assertFalse(html.contains("http://") || html.contains("https://"), "CDN 등 외부 URL 참조 없음");
        assertTrue(html.contains("fonts/"), "로컬 번들 폰트 상대 참조");
        if (theme == Theme.TYPE_B) {
            assertTrue(html.contains("src=\"mascot-face.png\""), "마스코트 클래스패스 번들 복사본 상대 참조(편차④)");
        }
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ5: rating 뱃지에 이모티콘이 없다(이식 편차①)")
    void ratingBadgeHasNoEmoji(Theme theme) {
        for (Rating rating : Rating.values()) {
            String html = render(theme, blendCard(rating));
            assertTrue(html.contains(rating.label()), "rating 라벨 표시: " + rating);
            for (String emoji : BANNED_EMOJIS) {
                assertFalse(html.contains(emoji), "이모티콘 없음: " + emoji);
            }
            if (theme == Theme.TYPE_B) {
                // th:styleappend 회귀 가드 — pill 형태(정적 style)와 4범주 색(동적)이 함께 남아야 한다.
                RatingStyle rs = new RatingStyle();
                assertTrue(html.contains("margin-left:auto") && html.contains("border-radius:999px"),
                        "뱃지 pill 정적 style 보존");
                assertTrue(html.contains("background:" + rs.bg(rating)), "뱃지 4범주 배경색: " + rating);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("값 없는 항목은 숨긴다 — rating·roastery·roastLevel 미기재, official_notes 빈 배열(구 AC-25 승계)")
    void hidesAbsentFields(Theme theme) {
        NoteView.ReviewCard sparse = new NoteView.ReviewCard(
                "핸드드립 하우스 블렌드", null,
                List.of(new NoteView.BeanLine("과테말라 안티구아", null)),
                null, List.of(),
                LocalDate.parse("2026-07-18"),
                "고소했음.", null);
        String html = render(theme, sparse);

        assertTrue(html.contains("과테말라 안티구아"), "원두 설명");
        assertTrue(html.contains("고소했음."), "감상 본문");
        assertFalse(html.contains("로스팅"), "roastLevel 없음 → 로스팅 행 숨김");
        assertFalse(html.contains("로스터리가 말하길") || html.contains(">노트<"), "official_notes 없음 → 영역 숨김");
        for (Rating rating : Rating.values()) {
            assertFalse(html.contains(rating.label()), "rating 없음 → 뱃지 숨김: " + rating);
        }
    }

    /**
     * 감상 카드 템플릿의 <b>바이트 동일성</b> — 개명(changes/0030)이 이 카드의 렌더 결과를 건드리지 않았음을
     * 파일 층에서 못 박는다(AC-Δ14, TΔ22).
     *
     * <p><b>왜 픽셀이 아니라 바이트인가</b>: AC-Δ14는 <i>"감상 카드의 렌더 결과가 개명 전후로 동일하다
     * (파일명·타입 값만 다르다)"</i>인데, 개명 전 산출을 지금 다시 만들 수 없으므로 픽셀 비교에는 비교 대상이
     * 없다. 대신 <b>렌더 결과를 결정하는 두 입력이 둘 다 안 움직였음</b>을 보이면 동일성이 따라온다:
     * ① 템플릿 바이트(이 단언) ② 바인딩 이름 — {@code card.myTaste}는 {@code my_taste} 유지 결정(D-Δ0-1)
     * 덕에 개명 대상이 아니었고, 나머지 바인딩({@code coffeeName}·{@code beans}·{@code rating}…)도 심볼
     * 4종 밖이다. 그래서 같은 회차를 넣으면 같은 HTML이 나오고, 갈리는 것은 산출 <b>파일명</b>뿐이다.
     *
     * <p><b>동일성의 출처는 git이다</b> — TΔ7이 {@code taste.html} → {@code review.html}을 순수 rename으로
     * 옮겼고(변경 0줄), 개명 전 blob 해시가 지금 파일의 해시와 같다:
     * {@code type-a cd8b6bb…} · {@code type-b 3a829b1…}. 아래 SHA-256은 그 시점의 바이트를 이 테스트가
     * 계속 물고 있게 하는 못이다.
     *
     * <p><b>이 못은 영구 잠금이 아니다</b> — 감상 카드 시안이 실제로 개정되면 값을 갱신한다
     * ({@code Change0021RegressionGuardTest.DESIGN_SHA256}과 같은 운용). 다만 그때는 «개정했다»가
     * 의도적 결정이어야 하고, 이 테스트가 레드가 되는 것이 그 확인 절차다.
     */
    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ14: 감상 카드 템플릿이 개명 전 taste.html과 바이트 동일하다 — 파일명만 바뀌었다")
    void templateIsByteIdenticalToPreRenameTasteHtml(Theme theme) throws Exception {
        String expected = switch (theme) {
            case TYPE_A -> "4861c8de897c9978a9287e26d597a5afbec0194537386daa09770aa2f36f57f2";
            case TYPE_B -> "af0995de4a94672b25af86a07d8c4c2242e3f39ddd3136024965ee8347922d2e";
        };
        Path template = Path.of("src/main/resources/templates", theme.id(), "review.html");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(template));

        assertEquals(expected, HexFormat.of().formatHex(digest),
                "감상 카드 템플릿이 바뀌었다 — 개명은 파일명만 옮기는 것이 결정이다(AC-Δ14, delta.md 비범위). "
                        + "시안 개정이 의도라면 이 값을 갱신할 것: " + template);
    }
}
