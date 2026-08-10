package com.devwuu.mocha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ8(changes/0030) Phase 1 게이트 — 개명이 끝난 뒤 <b>구 어휘 식별자가 프로덕션 소스에 0건</b>임을
 * 단언한다 (ref: changes/0030/delta.md#AC-Δ3, findings-TΔ0.md §2.6).
 *
 * <p>심볼 4종({@code tasting}→{@code review} · {@code brew}→{@code cup} · {@code entry}→{@code tasting_day} ·
 * {@code taste}→{@code review})을 TΔ1~TΔ7이 나눠 옮겼고, 각 단계의 완료 신호는 «빌드 그린»이었다.
 * 그런데 컴파일은 <b>남아 있는 구 어휘를 잡아주지 않는다</b> — 주석·문자열·계약 픽스처·eval 코퍼스는 전부
 * 컴파일 밖이다. 그 자리를 이 스캔이 진다.
 *
 * <p><b>⚠️ grep으로 짜면 안 된다</b>(TΔ2 실측): {@code contract/ClientApiContractTest.java}의 정렬 sentinel
 * {@code '￿'}를 보고 {@code grep -r}이 파일을 <b>바이너리로 판정해 통째로 건너뛴다</b>. 그러면 「0건」과
 * 「안 읽었다」가 구분되지 않는다. 그래서 파일을 직접 읽고, {@link #theScanActuallyReadsFiles()}가 읽은
 * 분량을, {@link #theScannerItselfCatchesOldVocabulary()}가 스캐너의 판정력을 각각 따로 단언한다 —
 * 셋이 함께여야 「0건」이 증거가 된다.
 *
 * <p><b>⚠️ 금지 토큰 {@code tasting}은 심볼 ③의 «새» 이름과 접두가 겹친다.</b> findings §2.6의 규칙 초안이
 * 쓰인 시점에 {@code tasting}은 심볼 ①의 구 이름이었는데, TΔ5a가 {@code entry}→{@code tasting_day}를
 * 옮기면서 같은 글자가 신 어휘로 되살아났다. 그래서 {@link #NEW_VOCABULARY_MASKS}가 <b>구 마스킹보다
 * 먼저</b> 걸린다 — 순서가 바뀌면 {@code TastingDayEntity}가 전부 위반으로 잡힌다.
 *
 * <p>대상에서 갈린 것들:
 * <ul>
 *   <li><b>{@code src/main/java}는 {@code *.java}만</b> — 같은 트리의 {@code CLAUDE.md:54}
 *       (<i>"Note/Entry 등 도메인 모델"</i>)는 문서라 TΔ24 몫이다(TΔ6 노트)</li>
 *   <li><b>{@code db/migration/**}은 통째로 대상 밖</b> — {@code V1}~{@code V3}은 Flyway 이력이라 불변이고,
 *       {@code V4}~{@code V6}은 <i>rename 문장 자체</i>라 구 이름이 있는 것이 정상이다</li>
 *   <li><b>{@code src/test/java/**}에는 걸지 않는다</b> — 구 이름의 «부재»를 단언하는 자리
 *       ({@code SchemaMigrationTest.RENAMED_AWAY})와 실제 제약 이름
 *       ({@code entry_note_id_tasted_on_key})과 폐기 tool 이력({@code send_entry_card})이 걸린다.
 *       AC-Δ3이 <i>"프로덕션 소스"</i>로 한정한 대로다</li>
 *   <li><b>eval 코퍼스를 넣는 것이 TΔ2·TΔ4·TΔ6의 실질 게이트다</b>(findings §7.2) — {@code EvalPathTest}는
 *       코퍼스를 한 줄도 안 고쳐도 통과하므로 개명을 증명하지 못한다</li>
 * </ul>
 *
 * <p><b>{@code @JsonProperty} 임시 조치 잔재는 이 스캔이 함께 잡는다</b> — a 단계들이 심었던 것은
 * {@code @JsonProperty("tasting")}·{@code ("brews")}·{@code ("entries")}뿐이고 셋 다 금지 토큰을
 * 인자로 물고 있다. 애너테이션 자체를 금지하지 않는 이유는 그것이 이 델타의 잔재가 아니라 정당한 Jackson
 * API이기 때문이다 — 금지하면 규칙이 델타보다 오래 살아남아 낡는다.
 */
class Change0030RenameGuardTest {

    /**
     * 스캔 대상 — {@code (루트, 확장자 필터, 필수 여부)}. {@code eval/cases}만 선택적이다:
     * 실발화라 비커밋 개인 데이터고(루트 CLAUDE.md §5, {@code .gitignore:9}) 클론 직후 환경에는 없는 것이
     * 정상이다({@code EvalCaseRunnerTest}의 스킵 안전과 같은 판단). 나머지는 없으면 그 자체가 실패다.
     */
    private record ScanRoot(String path, String extension, boolean required) {
    }

    private static final List<ScanRoot> SCAN_ROOTS = List.of(
            new ScanRoot("src/main/java", ".java", true),
            new ScanRoot("src/main/resources/templates", null, true),
            new ScanRoot("frontend/src", null, true),
            new ScanRoot("src/test/resources/eval", null, true),
            new ScanRoot("eval/cases", null, false));

    /**
     * 금지 토큰 — 대소문자를 구분하는 <b>부분 문자열</b> 대조다(findings §2.6).
     * <p>대문자 {@code BREW}가 없는 것은 의도다: {@code templates/type-a/recipe.html:27}의
     * {@code BREW RECIPE}는 카드에 인쇄되는 영문 디자인 라벨이고 갱신 시안에도 그대로 있다(TΔ3 노트).
     * 반면 {@code TASTE}는 구 {@code CardType.TASTE}라 금지 대상이다.
     */
    private static final List<String> BANNED = List.of(
            "Tasting", "tasting", "Brew", "brew", "brews", "Entry", "entry", "entries", "Taste", "taste", "TASTE");

    /**
     * 신 어휘 마스킹 — <b>반드시 먼저 걸린다</b>. 심볼 ③의 새 이름 {@code tasting_day} 계열이 금지 토큰
     * {@code tasting}과 접두를 공유하기 때문이다(클래스 javadoc 참조).
     * URL은 하이픈({@code /tasting-days/})·JSON은 언더스코어({@code tasting_days})라 둘 다 받는다.
     */
    private static final List<String> NEW_VOCABULARY_MASKS = List.of(
            "tasting[_-]day[A-Za-z_-]*",
            "tastingDay[A-Za-z]*",
            "TastingDay[A-Za-z0-9]*");

    /** 구 어휘처럼 보이지만 개명 대상이 아닌 것 — 전부 findings §2.1~2.5의 실측 오탐이다. */
    private static final List<String> FALSE_POSITIVE_MASKS = List.of(
            // §2.1 delta.md §어휘 매핑이 명시적으로 유지 — tasted_on·tastedOn·lastTasted·TASTED_ON
            "[Tt]asted[A-Za-z_]*",
            "TASTED[A-Z_]*",
            // §2.1 발화에서 날짜를 뽑는 탐지기 — 이 델타와 무관한 어휘
            "TastingDateDetector",
            // §2.5 D-Δ0-1로 «유지» 확정 — tasted_on과 같은 논리(이미 읽히는 이름)
            "my_taste[a-z_]*",
            "myTaste[A-Za-z]*",
            "MyTaste[A-Za-z0-9]*",
            // §2.2 언어·DOM API
            "entrySet",
            "Map\\.Entry",
            "SPA_ENTRY",
            "IntersectionObserver",
            // §2.4 0029에서 폐기된 tool 이름 — 존재하지 않는 tool을 개명하면 이력이 거짓이 된다
            "send_entry_card");

    @Test
    @DisplayName("AC-Δ3: 프로덕션 소스·템플릿·프론트·eval 코퍼스에 구 어휘 식별자가 0건이다")
    void productionSourcesCarryNoOldVocabulary() {
        List<String> violations = new ArrayList<>();
        for (Path file : filesToScan()) {
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String hit = firstBannedToken(lines.get(i));
                if (hit != null) {
                    violations.add(file + ":" + (i + 1) + " [" + hit + "] " + lines.get(i).strip());
                }
            }
        }
        assertThat(violations)
                .describedAs("구 어휘 식별자가 남아 있다(AC-Δ3) — 개명 대상이 아니라면 마스킹 규칙에 근거와 함께 추가한다")
                .isEmpty();
    }

    @Test
    @DisplayName("스캐너가 실제로 판정한다 — 구 어휘는 잡고 유지 어휘·신 어휘는 통과시킨다")
    void theScannerItselfCatchesOldVocabulary() {
        // 위 테스트의 «0건»은 스캐너가 아무것도 못 잡는 경우와 구분되지 않는다. 대조군을 여기서 세운다.
        assertThat(firstBannedToken("import com.devwuu.mocha.domain.Entry;")).isEqualTo("Entry");
        assertThat(firstBannedToken("private List<Brew> brews;")).isEqualTo("Brew");
        assertThat(firstBannedToken("@JsonProperty(\"entries\")")).isEqualTo("entries");
        assertThat(firstBannedToken("@JsonProperty(\"tasting\")")).isEqualTo("tasting");
        assertThat(firstBannedToken("TASTE(\"taste\")")).isEqualTo("taste");
        // ⚠️ 복수형은 «entry»가 아니라 «entries»로 잡힌다 — "entries"에 "entry"는 부분 문자열로 없다.
        // 금지 목록에 둘 다 있어야 하는 이유가 이것이고, 하나만 뒀다면 다른 쪽이 통째로 새어 나갔다.
        assertThat(firstBannedToken("PATCH /api/notes/{id}/entries/{date}")).isEqualTo("entries");
        assertThat(firstBannedToken("<date>-taste-<n>.jpg")).isEqualTo("taste");

        // 신 어휘 — 마스킹이 먼저 걸리지 않으면 여기가 전부 빨개진다(그리고 프로덕션 전체가 위반이 된다).
        assertThat(firstBannedToken("public TastingDayEntity toTastingDayEntity(Long noteId)")).isNull();
        assertThat(firstBannedToken("\"tasting_days\": [{\"tasting_day_id\": 1}]")).isNull();
        assertThat(firstBannedToken("PATCH /api/notes/{id}/tasting-days/{date}")).isNull();
        assertThat(firstBannedToken("const path = `/api/notes/${id}/tasting-days/${date}`;")).isNull();

        // 유지 확정 어휘 — 하나라도 잡히면 AC-Δ3이 델타가 유지하기로 한 것까지 지운다
        assertThat(firstBannedToken("private LocalDate tastedOn; // tasted_on")).isNull();
        assertThat(firstBannedToken("record Review(String myTaste, String my_taste_original) {}")).isNull();
        assertThat(firstBannedToken("new TastingDateDetector()")).isNull();
        assertThat(firstBannedToken("for (Map.Entry<String, String> e : m.entrySet())")).isNull();
        assertThat(firstBannedToken("private static final String SPA_ENTRY = \"index.html\";")).isNull();
        assertThat(firstBannedToken("// 구 send_entry_card는 0029에서 폐기됐다")).isNull();
        assertThat(firstBannedToken("<div class=\"label\">BREW RECIPE</div>")).isNull();
    }

    @Test
    @DisplayName("스캔이 실제로 파일을 읽는다 — 「0건」이 「안 읽었다」와 갈린다")
    void theScanActuallyReadsFiles() {
        List<Path> files = filesToScan();

        // 루트별로 본다 — 총계만 보면 한 루트가 통째로 빠져도 나머지가 채워 그린이 된다.
        for (ScanRoot root : SCAN_ROOTS) {
            if (!root.required() && Files.notExists(Path.of(root.path()))) {
                continue; // eval/cases 부재는 정상 (비커밋 개인 데이터)
            }
            assertThat(files).describedAs("스캔 루트 %s가 비었다", root.path())
                    .anyMatch(p -> p.startsWith(root.path()));
        }

        // TΔ2가 만난 함정의 대조군 — grep이 바이너리로 판정해 건너뛴 그 파일이 아니라, 그 sentinel과
        // 같은 성질의 파일들이 이 스캔에는 실제로 들어오는지 본다.
        assertThat(files).describedAs("계약을 낳는 소스가 스캔에 들어와야 한다")
                .anyMatch(p -> p.endsWith("NoteDetailBody.java"))
                .anyMatch(p -> p.endsWith("index.css"))
                .anyMatch(p -> p.endsWith("recipe.html"));

        assertThat(files).hasSizeGreaterThan(100);
    }

    // ─────────────────────── 스캔 기계 ───────────────────────

    /** 마스킹을 건 뒤 남은 첫 금지 토큰. 없으면 {@code null}. */
    private static String firstBannedToken(String line) {
        String masked = line;
        for (String mask : NEW_VOCABULARY_MASKS) {
            masked = masked.replaceAll(mask, "#");
        }
        for (String mask : FALSE_POSITIVE_MASKS) {
            masked = masked.replaceAll(mask, "#");
        }
        for (String token : BANNED) {
            if (masked.contains(token)) {
                return token;
            }
        }
        return null;
    }

    private static List<Path> filesToScan() {
        List<Path> files = new ArrayList<>();
        for (ScanRoot root : SCAN_ROOTS) {
            Path dir = Path.of(root.path());
            if (Files.notExists(dir)) {
                assertThat(root.required()).describedAs("필수 스캔 루트 %s가 없다", root.path()).isFalse();
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> root.extension() == null || p.toString().endsWith(root.extension()))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("스캔 대상을 읽지 못했다: " + file, e);
        }
    }
}
