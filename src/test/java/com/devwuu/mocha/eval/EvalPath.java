package com.devwuu.mocha.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * eval 케이스의 JSON 경로 — {@code tasting_days[0].cups[0].recipe.grind} (ref: changes/0026 TΔ1, ADR-68).
 * <p>JSONPath 같은 범용 질의 언어를 끌어오지 않는다: 케이스가 필요로 한 것은 <b>필드 하강 + 배열 인덱스</b>뿐이고
 * (findings-TΔ0 §2.3), 와일드카드·필터·재귀 하강은 쓰는 케이스가 없다(루트 CLAUDE.md §4 right-sizing).
 * 문법이 좁으면 오타가 라이브러리 안에서 "매치 없음"으로 조용히 넘어가지 않고 로더에서 사유와 함께 터진다(AC-Δ1).
 *
 * <p>필드명은 저장 포맷 그대로 <b>snake_case</b>다 — 판정 대상이 도메인 객체가 아니라 직렬화된 JSON이라서다.
 */
public record EvalPath(String raw, List<Segment> segments) {

    /** 경로 한 마디 — 필드 하강 또는 배열 인덱스. */
    public sealed interface Segment {

        /** {@code recipe} — 객체 필드 하강. */
        record Field(String name) implements Segment {
        }

        /** {@code [0]} — 배열 인덱스. */
        record Index(int index) implements Segment {
        }
    }

    // 필드명은 snake_case 저장 포맷을 그대로 받는다(영문·숫자·밑줄). 인덱스는 필드 뒤에만 붙는다 —
    // 최상위가 배열인 판정 대상이 없어(pending draft·tool 인자 전부 객체) 루트 인덱스는 문법에서 뺐다.
    private static final Pattern PATH = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\[\\d+])*(\\.[A-Za-z_][A-Za-z0-9_]*(\\[\\d+])*)*");
    private static final Pattern SEGMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)]");

    /**
     * 경로 문자열을 파싱한다.
     *
     * @throws IllegalArgumentException 문법 위반 — 로더가 사유로 감싸 보고한다(AC-Δ1).
     */
    public static EvalPath parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("경로가 비었다");
        }
        if (!PATH.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "경로 문법 오류: \"" + raw + "\" — 필드는 snake_case, 인덱스는 [n] 형태여야 한다"
                            + " (예: tasting_days[0].cups[0].recipe.grind)");
        }
        List<Segment> segments = new ArrayList<>();
        Matcher m = SEGMENT.matcher(raw);
        while (m.find()) {
            if (m.group(1) != null) {
                segments.add(new Segment.Field(m.group(1)));
            } else {
                segments.add(new Segment.Index(Integer.parseInt(m.group(2))));
            }
        }
        return new EvalPath(raw, List.copyOf(segments));
    }

    @Override
    public String toString() {
        return raw;
    }
}
