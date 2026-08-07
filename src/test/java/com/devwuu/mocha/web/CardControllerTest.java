package com.devwuu.mocha.web;

import com.devwuu.mocha.config.CommonConfig;
import com.devwuu.mocha.config.WebConfig;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.render.CardType;
import com.devwuu.mocha.render.NoteRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TΔ9(changes/0029): {@code GET /api/notes/{id}/entries/{date}/card} — 계약 구현 가드
 * (대조 기준 {@code contract/note-card.contract.json}).
 *
 * <p>검증의 축은 셋이다.
 * <ul>
 *   <li><b>대상 지정</b> — 경로·쿼리가 렌더러에 <i>그대로</i> 닿는가. {@code type}이 상수명이 아니라
 *       파일명과 같은 표기({@code taste})로 들어오므로 변환 규칙이 없으면 <b>모든 카드 요청이 400</b>이
 *       된다 — 그래서 {@link WebConfig}를 함께 들인다(슬라이스가 컨버터를 자동으로 얻지 않는다).</li>
 *   <li><b>바이트</b> — 응답이 JPEG이고 파일 내용 그대로인가. 파생물을 만드는 API라 <i>돌려준 것이
 *       그 파일인가</i>가 계약의 본체다.</li>
 *   <li><b>실패의 갈림</b> — 없는 자원은 404, 알 수 없는 종류는 400, 굽다 실패하면 500. 안내 문구의
 *       소유자는 화면이라는 규약이 여기서도 같다(TΔ6a).</li>
 * </ul>
 *
 * <p>렌더러는 손 fake다({@code org.mockito} 미도입 방침 유지) — 이 테스트의 대상은 <b>HTTP 표면</b>이고,
 * 캐시 히트/미스 판정은 {@code ThymeleafNoteRendererTest}가, 무효화는 {@code NoteServiceTest}가 소유한다.
 */
@WebMvcTest(controllers = CardController.class)
@Import({CommonConfig.class, WebConfig.class, CardControllerTest.Fakes.class})
class CardControllerTest {

    private static final String CONTRACT = "/contract/note-card.contract.json";

    private final JsonMapper mapper = MochaObjectMapper.create();

    @TempDir
    static Path cardsDir;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RecordingNoteRenderer renderer;

    private JsonNode contract;

    @BeforeEach
    void setUp() throws IOException {
        contract = load(CONTRACT);
        renderer.calls.clear();
        renderer.card = null;
        renderer.failure = null;
    }

    @Test
    @DisplayName("TΔ9: 경로·쿼리가 그대로 렌더러에 닿고 카드 바이트가 JPEG으로 돌아온다")
    void cardRequestReachesTheRendererAndReturnsBytes() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x42};
        renderer.card = write("2026-07-18-recipe-2.jpg", jpeg);

        byte[] body = mockMvc.perform(get("/api/notes/42/entries/2026-07-18/card")
                        .param("type", "recipe").param("n", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(jpeg);
        assertThat(renderer.calls).containsExactly("42 2026-07-18 RECIPE 2");
        assertThat(contract.get("response_status").intValue()).isEqualTo(200);
        assertThat(contract.get("response_content_type").stringValue()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("TΔ9: n을 안 주면 1회차다 — 회차가 하나뿐인 기록이 대다수다")
    void cupNumberDefaultsToOne() throws Exception {
        renderer.card = write("2026-07-18-taste-1.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        mockMvc.perform(get("/api/notes/42/entries/2026-07-18/card").param("type", "taste"))
                .andExpect(status().isOk());

        assertThat(renderer.calls).containsExactly("42 2026-07-18 TASTE 1");
    }

    @Test
    @DisplayName("POLICY: 카드는 no-store다 — 노트를 고치면 내용이 바뀌는 파생물이라 사진(30일 캐시)과 갈린다")
    void cardIsNotCachedByTheBrowser() throws Exception {
        renderer.card = write("2026-07-18-taste-1.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        mockMvc.perform(get("/api/notes/42/entries/2026-07-18/card").param("type", "taste"))
                .andExpect(header().string("Cache-Control", "no-store"))
                // 파일명은 내려받기 경로의 이름이 된다(공유 시트가 없는 환경의 폴백).
                .andExpect(header().string("Content-Disposition", "inline; filename=\"2026-07-18-taste-1.jpg\""));

        assertThat(contract.get("response_cache_control").stringValue()).isEqualTo("no-store");
    }

    @Test
    @DisplayName("AC-78: 없는 카드는 404다 — 레시피 없는 회차의 레시피 카드는 오류가 아니라 없는 자원이다")
    void absentCardIsNotFound() throws Exception {
        renderer.card = null; // 렌더러가 빈 Optional로 답한다

        mockMvc.perform(get("/api/notes/42/entries/2026-07-18/card").param("type", "recipe"))
                .andExpect(status().isNotFound());

        assertThat(contract.get("missing_status").intValue()).isEqualTo(404);
    }

    @Test
    @DisplayName("TΔ9: 알 수 없는 종류는 400 — 표기 판정의 소유자가 CardType 하나다")
    void unknownTypeIsRejected() throws Exception {
        mockMvc.perform(get("/api/notes/42/entries/2026-07-18/card").param("type", "thumbnail"))
                .andExpect(status().isBadRequest());

        assertThat(renderer.calls).isEmpty();
        assertThat(contract.get("bad_type_status").intValue()).isEqualTo(400);
    }

    @Test
    @DisplayName("plan §7: 굽다 실패하면 500이다 — 저장된 기록은 멀쩡하고 실패한 것은 파생물뿐이다")
    void renderFailureIsServerError() throws Exception {
        renderer.failure = new IllegalStateException("Chromium 기동 실패");

        mockMvc.perform(get("/api/notes/42/entries/2026-07-18/card").param("type", "taste"))
                .andExpect(status().isInternalServerError());

        assertThat(contract.get("render_failure_status").intValue()).isEqualTo(500);
    }

    private static Path write(String name, byte[] bytes) throws IOException {
        Path card = cardsDir.resolve(name);
        Files.write(card, bytes);
        return card;
    }

    private JsonNode load(String resource) throws IOException {
        try (InputStream in = CardControllerTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("계약 리소스 %s", resource).isNotNull();
            return mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** 렌더러 fake — <b>무엇을 요청받았는지</b>만 기록하고 미리 놓아 둔 파일을 답한다. */
    static final class RecordingNoteRenderer implements NoteRenderer {

        final List<String> calls = new ArrayList<>();
        Path card;
        RuntimeException failure;

        @Override
        public void renderAll() {
            throw new UnsupportedOperationException("이 표면은 전체 리렌더를 부르지 않는다");
        }

        @Override
        public Optional<Path> tastingDayCard(long noteId, LocalDate date, CardType type, int cupNumber) {
            calls.add(noteId + " " + date + " " + type + " " + cupNumber);
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(card);
        }

        @Override
        public List<Path> renderTastingDayCard(long noteId, LocalDate date) {
            throw new UnsupportedOperationException("온디맨드 미스는 구현체 안에서 일어난다");
        }
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        RecordingNoteRenderer noteRenderer() {
            return new RecordingNoteRenderer();
        }
    }
}
