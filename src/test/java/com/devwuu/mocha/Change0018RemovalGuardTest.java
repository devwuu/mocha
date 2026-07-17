package com.devwuu.mocha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ8b(changes/0018) 잔재 grep 게이트 — 구 단발 오케스트레이션의 참조가 main 소스·설정에 되살아나지
 * 않게 막는 회귀 가드 (ref: changes/0018/delta.md#AC-Δ8).
 * <p>대상 토큰: 폐기 클래스 4계열({@code IntentClassifier}·{@code SearchSession}·{@code TransitionSlot}·
 * {@code SearchClient})과 폐기 설정 키({@code mocha.llm.*}·{@code mocha.search.*}·
 * {@code mocha.search-session.*}). 키는 점 포함 전체 이름으로 대조한다 — 패키지명
 * {@code com.devwuu.mocha.llm}과의 오탐을 막기 위해서다.
 */
class Change0018RemovalGuardTest {

    // 폐기 클래스(AC-Δ8 명시 4계열) — 단순 부분 문자열 대조. VisionClient 등 생존 타입과 겹치지 않는 이름들이다.
    private static final List<String> BANNED_TYPES = List.of(
            "IntentClassifier", "SearchSession", "TransitionSlot", "SearchClient");

    // 폐기 설정 키(ADR-50) — 하위 키까지 전체 이름으로 대조한다.
    private static final List<String> BANNED_KEYS = List.of(
            "mocha.llm.model", "mocha.llm.max-retries",
            "mocha.search.model", "mocha.search.max-results",
            "mocha.search-session");

    @Test
    @DisplayName("AC-Δ8: 구 오케스트레이션 잔재 0 — main 소스·설정에 폐기 클래스·키 참조가 없다")
    void mainSourcesContainNoRemnants() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".yaml"))
                    .forEach(p -> scan(p, violations));
        }
        assertTrue(violations.isEmpty(),
                "구 오케스트레이션 잔재가 남아 있다(AC-Δ8):\n" + String.join("\n", violations));
    }

    private static void scan(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String token : BANNED_TYPES) {
                if (line.contains(token)) {
                    violations.add(file + ":" + (i + 1) + " [" + token + "] " + line.strip());
                }
            }
            for (String key : BANNED_KEYS) {
                if (line.contains(key)) {
                    violations.add(file + ":" + (i + 1) + " [" + key + "] " + line.strip());
                }
            }
        }
    }
}
