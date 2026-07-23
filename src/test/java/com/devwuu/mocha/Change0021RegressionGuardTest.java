package com.devwuu.mocha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TΔ7(changes/0021) UNCHANGED 단언 — 카드 2종 개정에서 "안 바뀜"을 선언한 항목의 회귀 가드
 * (ref: changes/0021/delta.md 영향 범위 UNCHANGED 행, AC-Δ9·Δ10·Δ11).
 *
 * <p>이 클래스는 기존 테스트가 커버하지 못한 두 갭만 직접 단언한다. 나머지 UNCHANGED 항목은
 * 아래 기존 테스트가 이미 가드한다(중복 단언하지 않음 — 위치 이동 시 이 표를 갱신할 것):
 * <ul>
 *   <li>카드 4:5(1080×1350) JPG 산출 — {@link com.devwuu.mocha.render.PlaywrightCardImageRendererTest},
 *       {@link com.devwuu.mocha.render.ThymeleafNoteRendererTest}(템플릿 뷰포트),
 *       Chromium 실렌더 스모크 2종(Taste/RecipeCardChromiumSmokeTest)</li>
 *   <li>[저장] 배달 경로(AC-17) — {@link com.devwuu.mocha.slack.SlackCommitHandlerTest}
 *       (커밋 → 증분 렌더 → 카드 배달, 부분 폴백 포함)</li>
 *   <li>send_entry_card 재전송 — {@link com.devwuu.mocha.agent.tool.ToolCallbackProviderTest}
 *       (재사용/증분/다장/부분 실패)</li>
 *   <li>하루 엔트리 2개 금지(AC-14) — {@link com.devwuu.mocha.repository.JsonFileNoteRepositoryTest}
 *       (같은 날 재기록 시 엔트리 수 불변)</li>
 *   <li>레시피 사용자 발화 전용(보강 금지) — {@link com.devwuu.mocha.agent.prompt.AgentSystemPromptTest}
 *       (recipe 전 필드 발화 전용 규칙)</li>
 *   <li>사진 아카이브 비렌더(ADR-32) — {@link com.devwuu.mocha.render.ThymeleafNoteRendererTest}
 *       (카드에 사진 요소 없음·리렌더 입력은 JSON뿐)</li>
 *   <li>렌더 외부 네트워크 0·aliases/pending 스키마 호환(AC-Δ9·Δ10 잔여) — 렌더러 setOffline 테스트,
 *       {@link com.devwuu.mocha.domain.DomainSerializationTest}</li>
 * </ul>
 */
class Change0021RegressionGuardTest {

    /**
     * design/ 시안 6종의 SHA-256 (2026-07-22 확정본). 시안은 디자인 source of truth(ADR-54)로
     * 수정 금지 — 이모티콘 제거 등 편차는 이식 템플릿에만 허용된다. 파일명 대신 체크섬 집합으로
     * 대조한다(macOS 유니코드 정규화 차이로 인한 한글 파일명 오탐 방지).
     */
    private static final Set<String> DESIGN_SHA256 = Set.of(
            "792a3d45c2be3cf1c435718a4644c51316a480c005e6a632689d54344151454a", // 귀여운 - 1 감상
            "0539bd2d2afe6c78691aa076203ad585a31c53ea0db1619894728c022455a432", // 귀여운 - 2 레시피 에스프레소
            "8fcda4458f6bf90c208a66de9eb63caa6b4070a29f7c0904a15a8efe81ed1577", // 귀여운 - 2 레시피 핸드드립
            "6a9b2e3a6f9e61869030b9a1be77798b85cae9b1619de3d6582676dfb0567ffb", // 세리프 - 1 감상
            "7c9abc2d33d8777a0e320a01a25231a2f42ffd82402c53d0c0ff35de9661cb6b", // 세리프 - 2 레시피 에스프레소
            "9be63b0b842019ed10c2cb5599421d741546b74c76f38fe48457045db2490b59"  // 세리프 - 2 레시피 핸드드립
    );

    @Test
    @DisplayName("AC-Δ10: design/ 시안 원본 6종이 확정본에서 변경되지 않았다(체크섬 대조)")
    void designMockupsUnchanged() throws IOException {
        Path designDir = Path.of("design");
        // design/은 gitignore된 로컬 전용 자산 — 없는 환경(fresh clone)에서는 대조 불가라 건너뛴다.
        assumeTrue(Files.isDirectory(designDir), "design/ 시안 폴더가 없는 환경 — 대조 생략");

        Set<String> actual;
        try (Stream<Path> files = Files.list(designDir)) {
            actual = files.filter(p -> p.getFileName().toString().endsWith(".dc.html"))
                    .map(Change0021RegressionGuardTest::sha256)
                    .collect(Collectors.toSet());
        }
        assertEquals(DESIGN_SHA256, actual,
                "design/ 시안 원본이 변경됐다 — 시안은 수정 금지, 편차는 이식 템플릿에만(ADR-54)");
    }

    @Test
    @DisplayName("AC-Δ4/ADR-54·55: 폐기 템플릿(note.html·index.html)이 리소스에 부활하지 않았다")
    void retiredTemplatesStayRemoved() {
        for (String theme : List.of("type-a", "type-b")) {
            for (String retired : List.of("note.html", "index.html")) {
                assertFalse(Files.exists(Path.of("src/main/resources/templates", theme, retired)),
                        "폐기 템플릿이 되살아났다: templates/" + theme + "/" + retired);
            }
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
