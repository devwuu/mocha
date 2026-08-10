package com.devwuu.mocha.agent.turn;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ2(changes/0029): <b>draft 스키마의 API 계약 가드</b> — {@link TurnDraft}의 JSON 직렬화형을 캡처본과
 * 바이트 단위로 비교한다. 이 형태가 곧 {@code POST /api/agent/turn}의 {@code draft} 필드이고(TΔ6에서 노출),
 * 클라이언트가 폼 상태를 실어 보낼 때 맞춰야 하는 계약이다
 * (ref: changes/0029 open-questions.md#OQ-4R, tasks TΔ2 — "draft 스키마를 API 계약으로 고정한다").
 * <p>왜 스냅샷인가: 서버가 draft를 들고 있지 않으므로(OQ-1 ㉡) 필드 이름 하나가 어긋나면 그 필드는
 * <b>조용히 사라진다</b> — 사용자가 폼에서 고친 값이 되돌아가는 이 델타의 실패(delta §1.2)가 그대로
 * 재현되는 경로다. 컴파일러가 잡아주지 않는 자리라 캡처본으로 못박는다.
 * <p>계약을 의도적으로 바꾸면(필드 추가·이름 변경) {@link #recaptureSnapshot()}을
 * {@code -Dmocha.contract.recapture=true}로 1회 실행한다 — 그 커밋의 diff가 곧 계약 변경의 리뷰 대상이고,
 * <b>클라이언트를 함께 고쳐야 한다는 신호</b>다.
 * <p>왕복 검증도 함께 둔다 — 클라이언트가 돌려보낸 JSON이 같은 draft로 복원되지 않으면 계약이 반쪽이다.
 */
class TurnDraftContractTest {

    private static final String SNAPSHOT_RESOURCE = "/contract/turn-draft.contract.json";

    private final JsonMapper mapper = MochaObjectMapper.create();

    @Test
    @DisplayName("TΔ2: draft JSON 형태 = 캡처 시점과 바이트 단위 동일 — 클라이언트와 공유하는 계약")
    void draftJsonMatchesSnapshot() throws IOException {
        assertThat(serializeCanonicalDraft()).isEqualTo(loadSnapshot());
    }

    @Test
    @DisplayName("TΔ2: 계약 JSON은 같은 draft로 왕복 복원된다 — 클라이언트가 돌려보낸 폼 상태가 유실되지 않는다")
    void draftJsonRoundTrips() {
        TurnDraft original = canonicalDraft();

        TurnDraft restored = mapper.readValue(mapper.writeValueAsString(original), TurnDraft.class);

        assertThat(restored).isEqualTo(original);
    }

    /**
     * 계약 캡처용 표준 draft — 필드 <b>종류</b>를 빠짐없이 덮는 것이 목적이다: 출처 표시 스칼라
     * ({@code coffee_name}·{@code roastery}·{@code roast_level}), 출처 표시 목록({@code official_notes}),
     * 원두 배열(V-14), 엔트리·회차·레시피·감상(V-15), 매칭 배지. 값 자체는 아무 의미 없는 고정 픽스처다.
     * <p>출처는 user·photo·search를 골고루 섞는다 — V-6 대조의 판정 입력이 계약에 실려 나가는지가
     * 이 스냅샷의 실질적 관심사다.
     * <p>캡처본에 {@code aliases}가 보이는 것은 draft가 {@link Note} 그대로이기 때문이다(별도 DTO를 두지
     * 않는다). 별칭은 커밋 경로에서만 채워지므로 제안 단계 draft에서는 항상 빈 값이고, 클라이언트는 받은
     * 것을 그대로 돌려보내면 된다 — 내부 전용 표시 금지(V-13)는 렌더·미리보기 규칙이라 여기 적용 대상이
     * 아니다. 노출 자체가 거슬리면 TΔ6(REST 계약 확정)에서 잘라내는 것이 제자리다.
     */
    private static TurnDraft canonicalDraft() {
        OffsetDateTime timestamp = OffsetDateTime.of(2026, 7, 16, 10, 0, 0, 0, ZoneOffset.ofHours(9));
        TastingDay tastingDay = new TastingDay(LocalDate.of(2026, 7, 16), List.of(new Cup(
                new Recipe("핸드드립", 15.0, 240.0, 220.0, 160.0, 92.0, 210.0,
                        "매버릭 2.0", "V60 · 40초 뜸 후 3회 분할", "퍽이 물퍽이었음"),
                new Review("새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD))), timestamp);
        Note note = new Note(
                null,
                new Sourced<>("Ethiopia Chelbesa", Source.USER),
                new Sourced<>("FroB", Source.PHOTO),
                List.of(new Bean(new Sourced<>("에티오피아 게데오 헤어룸", Source.SEARCH),
                        new Sourced<>("워시드", Source.SEARCH))),
                new Sourced<>("라이트", Source.SEARCH),
                new Sourced<>(List.of("자스민", "베르가못"), Source.SEARCH),
                List.of("https://example.test/chelbesa"),
                List.of(tastingDay),
                timestamp, timestamp);
        return new TurnDraft(note, MatchInfo.newNote());
    }

    private String serializeCanonicalDraft() {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(canonicalDraft());
    }

    private static String loadSnapshot() throws IOException {
        try (InputStream snapshot = TurnDraftContractTest.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
            assertThat(snapshot).as("스냅샷 리소스 %s", SNAPSHOT_RESOURCE).isNotNull();
            return new String(snapshot.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 재캡처 유틸 — {@code AgentModelContractSnapshotTest}와 같은 프로퍼티 게이트 관례다:
     * {@code ./gradlew test --tests '*TurnDraftContractTest*' -Dmocha.contract.recapture=true}.
     * 같은 실행의 가드 테스트는 재캡처 <b>전</b> classpath 복사본과 비교하므로 의도된 변경 중이면 레드가 정상이다.
     */
    @Test
    @EnabledIfSystemProperty(named = "mocha.contract.recapture", matches = "true",
            disabledReason = "계약 변경이 spec으로 결정됐을 때만 -Dmocha.contract.recapture=true로 재캡처한다")
    void recaptureSnapshot() throws IOException {
        Path target = projectRoot().resolve("src/test/resources" + SNAPSHOT_RESOURCE);
        Files.createDirectories(target.getParent());
        String contract = serializeCanonicalDraft();
        Files.writeString(target, contract, StandardCharsets.UTF_8);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(contract);
    }

    // 프로젝트 루트 앵커 — 러너의 working directory에 기대지 않는다(IDE 실행 구성의 CWD 편차 무해화).
    private static Path projectRoot() {
        try {
            Path start = Path.of(TurnDraftContractTest.class
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
