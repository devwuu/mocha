package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.agent.tool.BeanArg;
import com.devwuu.mocha.agent.tool.SourcedArg;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 출처 규칙 패밀리(V-5·V-14) — 출처 표시 인자를 도메인 {@link Sourced} 값으로 정규화하고, 값이 있는데
 * 출처가 없거나 허용 밖이면 사유와 함께 거부한다. record·edit 양 진입점이 공유한다
 * (ref: specs/coffee-note-agent/data-model.md#5, plan.md#ADR-64 — 추출은 배치 변경, 판정·문안 불변).
 */
final class SourceRules {

    // V-5: coffee_name은 검색 보강 대상이 아니다 — 검색 앵커이자 정체성 (ref: data-model.md#2.1).
    static final Set<Source> COFFEE_NAME_SOURCES = Set.of(Source.USER, Source.PHOTO);
    static final Set<Source> ENRICHABLE_SOURCES = Set.of(Source.USER, Source.PHOTO, Source.SEARCH);

    private SourceRules() {
    }

    /**
     * 출처 표시 인자 → 도메인 {@link Sourced} (V-5). value가 비면 null로 정규화하고,
     * value가 있는데 source가 없거나 허용 밖이면 사유와 함께 거부한다.
     */
    static Sourced<String> sourced(String field, SourcedArg<String> arg, Set<Source> allowed) {
        if (arg == null || ValidationSupport.blankToNull(arg.value()) == null) {
            return null;
        }
        return new Sourced<>(arg.value().strip(), parseSource(field, arg.source(), allowed));
    }

    /** official_notes 변형 — 항목의 공백을 걷어내고 전무면 null (V-5는 동일 적용). */
    static Sourced<List<String>> sourcedNotes(SourcedArg<List<String>> arg) {
        if (arg == null) {
            return null;
        }
        List<String> notes = ValidationSupport.dropBlanks(arg.value());
        if (notes.isEmpty()) {
            return null;
        }
        return new Sourced<>(notes, parseSource("official_notes", arg.source(), ENRICHABLE_SOURCES));
    }

    // V-14: beans 인자 → 도메인 Bean 배열. 서브필드 source는 V-5로 검증(위반은 사유 있는 거부)하고,
    // 빈 description 요소 드롭·빈 process null 정규화는 Bean.normalize가 맡는다(저장 거부 아님).
    static List<Bean> beans(List<BeanArg> raw) {
        if (raw == null) {
            return List.of();
        }
        List<Bean> converted = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            BeanArg arg = raw.get(i);
            if (arg == null) {
                continue;
            }
            converted.add(new Bean(
                    sourced("beans[" + i + "].description", arg.description(), ENRICHABLE_SOURCES),
                    sourced("beans[" + i + "].process", arg.process(), ENRICHABLE_SOURCES)));
        }
        return Bean.normalize(converted);
    }

    private static Source parseSource(String field, String raw, Set<Source> allowed) {
        if (raw == null) {
            throw new RejectedException(field + "의 source가 없다 — 값을 채웠으면 출처(" +
                    allowedLabels(allowed) + ")를 함께 보고해라(V-5).");
        }
        Source source;
        try {
            source = Source.from(raw);
        } catch (IllegalArgumentException e) {
            throw new RejectedException(field + "의 source '" + raw + "'는 허용되지 않는다 — "
                    + allowedLabels(allowed) + " 중 하나여야 한다(V-5).");
        }
        if (!allowed.contains(source)) {
            throw new RejectedException(field + "의 source '" + raw + "'는 허용되지 않는다 — "
                    + allowedLabels(allowed) + " 중 하나여야 한다(V-5).");
        }
        return source;
    }

    private static String allowedLabels(Set<Source> allowed) {
        // Set 순회 순서 비결정 방지 — 선언 순서(user, photo, search)로 고정해 사유 문구를 결정론으로.
        StringBuilder labels = new StringBuilder();
        for (Source source : Source.values()) {
            if (allowed.contains(source)) {
                labels.append(labels.isEmpty() ? "" : "|").append(source.json());
            }
        }
        return labels.toString();
    }
}
