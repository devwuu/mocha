package com.devwuu.mocha.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 노트의 내부 매칭·검색 전용 별칭 — {@code { "coffee_name": [...], "roastery": [...] }}.
 * <p>카드·인덱스·미리보기 어디에도 표시하지 않는다(V-13). 축적 두 갈래:
 * ① 신규 노트 첫 [저장] 시 LLM 1콜(AliasGenerator)이 생성하는 음차·이표기,
 * ② 이후 같은 노트로 매칭된 기록의 관측 표기(무콜 서버 축적).
 * <p>항목은 정규화(소문자화·공백 제거) 기준으로 중복 제거해 저장하되, <b>표시 형태(첫 등장)는 보존</b>한다
 * — 정규화는 대조·중복 제거의 <i>기준</i>일 뿐 저장값이 아니다(예: "에티오피아 첼베사"는 공백을 유지).
 * (ref: specs/coffee-note-agent/data-model.md#2.1, V-13; changes/0016, plan ADR-37)
 *
 * @param coffeeName 커피명 이표기(원문 표시값 자체는 여기 중복 수록하지 않는다 — 대조 시 별도 포함).
 * @param roastery   로스터리 이표기.
 */
public record Aliases(List<String> coffeeName, List<String> roastery) {

    /** null/부재 컴포넌트는 빈 목록으로, 각 목록은 정규화 기준 중복 제거해 정규화한다. */
    public Aliases {
        coffeeName = dedupNormalized(coffeeName);
        roastery = dedupNormalized(roastery);
    }

    /** 별칭 부재(기존 노트 로드·생성 콜 실패 수렴)의 표준 빈 값. */
    public static Aliases empty() {
        return new Aliases(List.of(), List.of());
    }

    /**
     * 관측 표기 무콜 축적(TΔ3) — EXISTING 매칭 커밋 시 이번 기록의 커피명·로스터리 표시값을 별칭에 더한다.
     * <p>노트의 표시값(canonical)과 정규화 일치하는 관측 표기는 별칭에 넣지 않는다 — 표시값 자체는 별칭에
     * 중복 수록하지 않기 때문(대조 시 별도 포함, V-13). 그 밖의 표기만 기존 별칭과 정규화 중복 제거로 병합한다
     * (중복·표시값·빈 표기는 미추가). LLM 콜 없이 서버가 축적한다.
     * (ref: specs/coffee-note-agent/changes/0016 delta AC-Δ4, plan.md#ADR-37 축적 ②)
     *
     * @param observedCoffeeName  이번 기록의 커피명 표시값(추출·OCR 유래). null·공백 허용.
     * @param canonicalCoffeeName 노트의 커피명 표시값 — 이와 정규화 일치하는 관측 표기는 별칭에 넣지 않는다.
     * @param observedRoastery    이번 기록의 로스터리 표시값(추출·OCR 유래). null·공백 허용.
     * @param canonicalRoastery   노트의 로스터리 표시값.
     * @return 관측 표기를 반영한 새 {@link Aliases}(불변). 반영할 신규 표기가 없으면 내용상 동일하다.
     */
    public Aliases accumulate(String observedCoffeeName, String canonicalCoffeeName,
                              String observedRoastery, String canonicalRoastery) {
        return new Aliases(
                accumulateInto(coffeeName, observedCoffeeName, canonicalCoffeeName),
                accumulateInto(roastery, observedRoastery, canonicalRoastery));
    }

    /**
     * 관측 표기 1건을 별칭 목록에 더한다 — 표시값(canonical)과 정규화 일치하거나 빈 표기면 그대로 두고,
     * 그 밖이면 목록 끝에 붙인 뒤 정규화 중복 제거(기존 별칭과의 중복도 흡수). 기존 순서는 보존된다.
     */
    private static List<String> accumulateInto(List<String> aliases, String observed, String canonical) {
        String key = normalize(observed);
        if (key.isEmpty() || key.equals(normalize(canonical))) {
            return aliases;
        }
        List<String> merged = new ArrayList<>(aliases);
        merged.add(observed);
        return dedupNormalized(merged);
    }

    /**
     * 정규화 키 — 소문자화 + 모든 공백 제거. 대조·중복 제거의 <b>기준</b>이며 저장값이 아니다(V-13).
     * null·빈 문자열은 빈 키("")로 수렴한다.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * 정규화 기준 중복 제거 — 첫 등장 표시 형태를 보존하고, null·공백·중복(정규화 일치)은 버린다.
     * 순서는 입력 순서를 유지한다.
     */
    public static List<String> dedupNormalized(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String key = normalize(value);
            if (key.isEmpty()) {
                continue;
            }
            if (seen.add(key)) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }
}
