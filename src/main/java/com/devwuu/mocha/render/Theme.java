package com.devwuu.mocha.render;

import java.util.Arrays;
import java.util.List;

/**
 * 렌더 테마 — {@code mocha.artifact.theme}로 선택하는 노트/인덱스 디자인 세트 (ref: tasks T5-1).
 * <p>디자인은 {@code type-a}(세리프·명조), {@code type-b}(귀여운·고딕) 두 종으로 일반화한다. 값은
 * 그대로 {@code templates/<value>/} 하위 템플릿 폴더명이 된다 — 테마를 추가하려면 폴더 하나를 더 두고
 * enum 상수를 늘리면 된다(파이프라인/렌더러 변경 없음).
 * <p>POLICY: 렌더러는 JSON 외 어떤 상태도 읽지 않는다 — 테마는 표현만 바꾼다(ref: plan.md#ADR-1, AC-6).
 */
public enum Theme {
    /** 세리프(명조·액자) 노트/인덱스. Gowun Batang Regular·Bold. */
    TYPE_A("type-a", List.of("GowunBatang-Regular.ttf", "GowunBatang-Bold.ttf")),
    /** 귀여운(고딕·카드·마스코트) 노트/인덱스. Gowun Dodum Regular. */
    TYPE_B("type-b", List.of("GowunDodum-Regular.ttf"));

    private final String id;
    private final List<String> fonts;

    Theme(String id, List<String> fonts) {
        this.id = id;
        this.fonts = fonts;
    }

    /** {@code templates/<id>/} 폴더명이자 {@code mocha.artifact.theme} 설정 값. */
    public String id() {
        return id;
    }

    /**
     * 이 테마 카드/인덱스가 참조하는 로컬 번들 폰트 파일명들 (ref: plan.md#ADR-11).
     * <p>렌더러가 {@code assets/fonts/<name>} → {@code artifact/fonts/<name>}로 복사해 카드 CSS의
     * {@code @font-face}가 CDN 없이 해석되게 한다(오프라인·결정적 래스터화).
     */
    public List<String> fontFiles() {
        return fonts;
    }

    /** 설정 값 → Theme. 대소문자·앞뒤 공백은 허용하고, 미상 값은 거부한다. */
    public static Theme from(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(t -> t.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "알 수 없는 테마(mocha.artifact.theme): '" + value + "' — 허용: type-a, type-b"));
    }
}
