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
 *       Chromium 실렌더 스모크 2종(Review/RecipeCardChromiumSmokeTest)</li>
 *   <li>~~[저장] 배달 경로(AC-17)~~ — 버튼 커밋 체인이 changes/0029 TΔ4에서 폐기됐고 Slack 계층은
 *       TΔ16에서 삭제됐다. 저장 확정은 {@link com.devwuu.mocha.web.NoteControllerTest}가 가드하고,
 *       카드 배달은 온디맨드 공유로 형태가 바뀌어 TΔ9가 그 자리를 다시 세운다.</li>
 *   <li>~~send_entry_card 재전송~~ — tool이 changes/0029 TΔ1에서 폐기됐다(갤러리·상세 화면이 대체).</li>
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
     * design/ 시안 8종의 SHA-256. 시안은 디자인 source of truth(ADR-54)로 수정 금지 — 이모티콘 제거 등
     * 편차는 이식 템플릿에만 허용된다. 파일명 대신 체크섬 집합으로 대조한다(macOS 유니코드 정규화 차이로
     * 인한 한글 파일명 오탐 방지).
     * <p>카드 6종이 2026-07-22 확정본이고, <b>앱 화면 3종은 changes/0029에서 편입됐다</b>(2026-08-01,
     * 사용자 확정) — 앱이 인터페이스를 가져가며 카드 밖 화면에도 시안이 생겼고, 같은 규율("시안은 수정
     * 금지")을 적용한다. 집합 <b>동등성</b> 단언이라 추가도 레드가 되는데, 그것이 의도다: 시안 추가는
     * 디자인 source of truth가 늘어나는 일이므로 이 목록을 함께 고치는 것이 정상 경로다.
     * <p><b>changes/0030 TΔ0에서 레시피 시안이 4종 → 2종으로 통합됐다</b>(2026-08-02, 사용자 확정) —
     * 방식별(에스프레소·핸드드립) 시안이 갈려 있던 것이 단일 레이아웃 1종으로 합쳐졌고, 이 통합이 곧
     * ADR-87(카드 방식 분기 폐기)의 디자인 근거다. 갱신된 2종은 TΔ20·TΔ21의 이식 원본이다.
     */
    private static final Set<String> DESIGN_SHA256 = Set.of(
            "792a3d45c2be3cf1c435718a4644c51316a480c005e6a632689d54344151454a", // 귀여운 - 1 감상
            "c4027980e36840e15e221da48fec10ab33624b7858bc0eb0924f40073b15d219", // 귀여운 - 2 레시피 (0030 통합본)
            "6a9b2e3a6f9e61869030b9a1be77798b85cae9b1619de3d6582676dfb0567ffb", // 세리프 - 1 감상
            "4d277b21f8cf29bd5ab539c0866484e5c2030296c69233b6ab745a1213ea29f5", // 세리프 - 2 레시피 (0030 통합본)
            "f235e2186b7dfd6a7cedabb12131c65b2e3f027ae637c600d18525c2fff829cf", // 리스트 - 갤러리 (0029 S2)
            "9895f4492386faafd77d613054e85bfe2b08406f338ff27a488570b641b78947", // 채팅 - 말풍선 (0029 S1)
            "f5304f6128403fc695af6c89d3db6ebbadf4ca88f6043c2df0572db0efe8a961", // 노트 상세 (0029 S2, TΔ13a)
            "e2b857e111adcdcb95ae0046373fcb4523dd7ecccca10aa771ff9d7225c47d47"  // 노트 수정 (0029 S2, TΔ13b)
    );

    @Test
    @DisplayName("AC-Δ10: design/ 시안 원본 8종이 확정본에서 변경되지 않았다(체크섬 대조)")
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
