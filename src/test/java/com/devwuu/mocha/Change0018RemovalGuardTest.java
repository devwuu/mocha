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
 * <p>대상 토큰: 폐기 클래스 3계열({@code IntentClassifier}·{@code SearchSession}·{@code TransitionSlot})과
 * 폐기 설정 키({@code mocha.llm.*}·{@code mocha.search-session.*}). 키는 점 포함 전체 이름으로 대조한다 —
 * 패키지명 {@code com.devwuu.mocha.llm}과의 오탐을 막기 위해서다.
 *
 * <p><b>⚠️ changes/0029 TΔ24b에서 {@code SearchClient}·{@code mocha.search.*}가 대상에서 빠졌다.</b>
 * 0018이 이것들을 금지 토큰으로 박은 근거는 <i>"보강은 이제 에이전트 루프 안의 모델 재량 tool"</i>(ADR-49)
 * 이었는데, <b>그 전제 자체가 회귀의 원인으로 밝혀져 개정됐다</b> — 모델이 부르지 않기로 하면 보강이
 * 조용히 죽고 아무 테스트도 빨개지지 않았다(delta 0029 D-16 ①·②). 보강이 결정론 단계로 되돌아간 이상
 * 이 토큰들은 «잔재»가 아니라 «현행 구조»다.
 * <p>남은 3계열은 그대로 금지다 — 그것들이 표현하던 <i>단발 파이프라인·세션 상태·전이 슬롯</i>은 되살아나지
 * 않았고, 0018이 지운 진짜 복잡도가 거기 있었다(D-16 ④: 2단계 이미지 OCR 배관도 부활 대상이 아니다).
 * <p>POLICY: 규칙이 딛는 구조가 바뀌면 그 규칙을 함께 개정하거나 명시적으로 유지 선언한다 — 규칙만 남아
 * 낡는 것을 막는다 (ref: plan.md#ADR-82 POLICY; 그 ADR을 낳은 것이 V-10을 지나친 changes/0021이다).
 */
class Change0018RemovalGuardTest {

    // 폐기 클래스 — 단순 부분 문자열 대조. VisionClient 등 생존 타입과 겹치지 않는 이름들이다.
    // 구 4계열 중 SearchClient는 changes/0029 TΔ24b에서 부활했다(클래스 javadoc 참조) — SearchSession은
    // 그대로 금지이므로 "Search"로 시작하는 이름이라고 함께 풀리지 않는다.
    private static final List<String> BANNED_TYPES = List.of(
            "IntentClassifier", "SearchSession", "TransitionSlot");

    // 폐기 설정 키(ADR-50) — 하위 키까지 전체 이름으로 대조한다.
    // mocha.search.model·max-results는 TΔ24b에서 부활했고, mocha.search-session은 세션 상태와 함께 폐기 유지다.
    private static final List<String> BANNED_KEYS = List.of(
            "mocha.llm.model", "mocha.llm.max-retries",
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
