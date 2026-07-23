package com.devwuu.mocha.agent;

import com.devwuu.mocha.agent.prompt.AgentSystemPrompt;
import com.devwuu.mocha.agent.tool.ToolCallback;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ0b(changes/0024): 모델 대면 계약 스냅샷 가드 — tool 5종의 name·description·parametersSchema와
 * 시스템 프롬프트를 재정비 착수 시점 캡처와 <b>바이트 단위</b>로 비교한다 (AC-Δ4, delta UNCHANGED 표).
 * <p>구조 재정비(조립 이관·패키지 분할·명명 정리)의 전 과정에서 이 테스트가 그린이어야 한다 —
 * 배치·배선만 바뀌고 <b>이 가드의 범위(tool 5종 정의 + 시스템 프롬프트)</b>는 안 바뀌었음의 증거다.
 * 모델 대면 표면의 나머지 — 드라이버가 장착하는 내장 web_search(OpenAiAgentClient)와 턴 컨텍스트
 * 조립(TurnPromptAssembler) — 는 이 스냅샷 밖이며 각자의 테스트가 가드한다.
 * <p>의도된 계약 변경(spec 델타로 결정된 프롬프트·스키마 수정)이 생기면 {@link #recaptureSnapshot()}을
 * {@code -Dmocha.contract.recapture=true}로 1회 실행해 재캡처한다 — 그 커밋에서 스냅샷 diff 자체가
 * 계약 변경의 리뷰 대상이 된다.
 */
class AgentModelContractSnapshotTest {

    private static final String SNAPSHOT_RESOURCE = "/contract/agent-model-contract.snapshot.txt";

    @Test
    @DisplayName("AC-Δ4: tool 5종 name·description·parametersSchema + 시스템 프롬프트 = 캡처 시점과 바이트 단위 동일")
    void modelFacingContractMatchesSnapshot() throws IOException {
        assertThat(serializeCurrentContract()).isEqualTo(loadSnapshot());
    }

    /**
     * 현행 계약의 결정론 직렬화 — {@code forTurn}이 장착하는 순서 그대로 tool 정의를 늘어놓고
     * 시스템 프롬프트로 닫는다. 턴별 인자(userId·channelId·utterance)는 executor 클로저에만 쓰이고
     * 정의(name·description·schema)에는 영향이 없다 — 더미로 충분하다.
     */
    private static String serializeCurrentContract() {
        // 협력자는 전부 null — executor는 호출하지 않고 정의만 캡처한다(정의는 협력자 무관 상수).
        ToolCallbackProvider toolkit = new ToolCallbackProvider(null, null, null, null, null, null,
                null, null, null, null, null);
        List<ToolCallback> tools = toolkit.forTurn("U-snapshot", "C-snapshot", new TurnUserMessage("스냅샷", null));

        StringBuilder contract = new StringBuilder();
        for (ToolCallback tool : tools) {
            contract.append("### tool: ").append(tool.name()).append('\n')
                    .append("--- description\n").append(tool.description()).append('\n')
                    .append("--- parametersSchema\n").append(tool.parametersSchema()).append('\n')
                    .append('\n');
        }
        contract.append("### system-prompt\n").append(AgentSystemPrompt.INSTRUCTIONS);
        return contract.toString();
    }

    private static String loadSnapshot() throws IOException {
        try (InputStream snapshot = AgentModelContractSnapshotTest.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
            assertThat(snapshot).as("스냅샷 리소스 %s", SNAPSHOT_RESOURCE).isNotNull();
            return new String(snapshot.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 재캡처 유틸 — 의도된 계약 변경 시에만 시스템 프로퍼티 게이트로 1회 실행해 스냅샷을 갱신한다:
     * {@code ./gradlew test --tests '*AgentModelContractSnapshotTest*' -Dmocha.contract.recapture=true}.
     * 평상시 비활성: 상시 돌면 가드가 "현재 상태 = 기대값"으로 무력화되기 때문이다 — 소스 편집({@code @Disabled}
     * 해제) 방식은 해제 상태로 커밋되는 실수 경로가 있어 프로퍼티 게이트로 둔다.
     * <p>주의: 같은 실행에서 {@link #modelFacingContractMatchesSnapshot()}은 여전히 <b>재캡처 전</b>
     * classpath 복사본과 비교한다(의도된 변경 중이면 레드가 정상) — 재캡처 반영 확인은 다음 실행에서 한다.
     */
    @Test
    @EnabledIfSystemProperty(named = "mocha.contract.recapture", matches = "true",
            disabledReason = "계약 변경이 spec으로 결정됐을 때만 -Dmocha.contract.recapture=true로 재캡처한다")
    void recaptureSnapshot() throws IOException {
        Path target = projectRoot().resolve("src/test/resources" + SNAPSHOT_RESOURCE);
        Files.createDirectories(target.getParent());
        String contract = serializeCurrentContract();
        Files.writeString(target, contract, StandardCharsets.UTF_8);
        // 즉석 재검증 — 방금 쓴 파일 기준. 가드 테스트는 stale classpath 복사본을 읽으므로 이 단언이
        // "쓴 내용 = 현행 계약"의 확인을 대신한다(인코딩 왕복 포함).
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(contract);
    }

    /**
     * 프로젝트 루트 앵커 — 러너의 working directory에 기대지 않고, 컴파일된 테스트 클래스 위치에서
     * {@code settings.gradle}이 있는 디렉터리까지 거슬러 올라 찾는다(IDE 실행 구성의 CWD 편차 무해화).
     */
    private static Path projectRoot() {
        try {
            Path start = Path.of(AgentModelContractSnapshotTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                if (Files.exists(dir.resolve("settings.gradle"))) {
                    return dir;
                }
            }
            throw new IllegalStateException("settings.gradle 미발견 — 탐색 시작점: " + start);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("테스트 클래스 위치를 경로로 변환하지 못했다", e);
        }
    }
}
