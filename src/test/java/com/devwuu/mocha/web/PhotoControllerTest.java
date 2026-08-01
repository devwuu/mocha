package com.devwuu.mocha.web;

import com.devwuu.mocha.config.CommonConfig;
import com.devwuu.mocha.image.TestImages;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.service.PhotoService;
import com.devwuu.mocha.repository.RecordingPhotoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TΔ8a(changes/0029): {@code POST /api/photos} — 계약 구현 가드
 * (대조 기준 {@code contract/photo-upload.contract.json}).
 *
 * <p>검증의 축은 <b>계약의 왕복</b>이다: multipart로 진짜 JPEG/PNG를 올리고 응답이 계약이 선언한 모양
 * ({@code {photos:[{name}]}})인지, 그리고 <b>거부가 상태 코드로 갈리는지</b>를 본다 — 안내 문구의 소유자는
 * 화면이라는 규약이 여기서도 같다(TΔ6a).
 *
 * <p>service는 실물이다({@code RecordingPhotoStore} 위에 세운다) — 이 표면의 값이
 * <i>"게이트를 지난 것만 스테이징에 닿는다"</i>인데 service를 스텁으로 가리면 그 사실이 검증에서 빠진다.
 *
 * <p>{@link CommonConfig}를 함께 들이는 것은 도메인 JSON 규칙(snake_case)의 소유자가 그 빈이기 때문이다.
 */
@WebMvcTest(controllers = PhotoController.class)
@Import({CommonConfig.class, PhotoControllerTest.Fakes.class})
class PhotoControllerTest {

    private static final String CONTRACT = "/contract/photo-upload.contract.json";

    private final JsonMapper mapper = MochaObjectMapper.create();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RecordingPhotoStore photoStore;

    private JsonNode contract;

    @BeforeEach
    void setUp() throws IOException {
        contract = load(CONTRACT);
        photoStore.staged.clear();
        photoStore.discarded.clear();
    }

    @Test
    @DisplayName("TΔ8a: 업로드 응답이 계약 모양이다 — {photos:[{name}]}, 스테이징 파일명뿐")
    void uploadAnswersWithStagedNames() throws Exception {
        String body = mockMvc.perform(multipart("/api/photos")
                        .file(new MockMultipartFile("photos", "bag-front.jpg", "image/jpeg", TestImages.jpeg()))
                        .file(new MockMultipartFile("photos", "bag-back.jpg", "image/jpeg", TestImages.jpeg())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode response = mapper.readTree(body);
        assertThat(response.propertyNames()).containsExactly("photos");
        assertThat(response.get("photos").valueStream().map(photo -> photo.get("name").stringValue()))
                .containsExactly("bag-front.jpg", "bag-back.jpg");
        // 계약 예시가 그리는 이름과 같은 형태여야 한다 — 화면이 이 값을 다음 턴에 되싣는다.
        assertThat(response.get("photos").valueStream().map(JsonNode::propertyNames).map(java.util.ArrayList::new))
                .allSatisfy(names -> assertThat(names).containsExactly("name"));
        assertThat(contract.get("response_status").intValue()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-7: 스테이징에 닿는 바이트에서 EXIF가 걷혔다 — 업로드 경로가 실제로 그것을 지난다")
    void uploadStripsExifBeforeStaging() throws Exception {
        mockMvc.perform(multipart("/api/photos")
                        .file(new MockMultipartFile("photos", "bag.jpg", "image/jpeg", TestImages.jpegWithExif())))
                .andExpect(status().isOk());

        assertThat(photoStore.staged).hasSize(1);
        assertThat(new String(photoStore.staged.get(0).bytes(), StandardCharsets.ISO_8859_1))
                .doesNotContain(TestImages.SECRET);
    }

    @Test
    @DisplayName("ADR-29: 수용하지 않는 포맷은 400이고 한 장도 스테이징되지 않는다 — 판별은 매직바이트다")
    void unacceptedFormatIsRejectedWithoutStaging() throws Exception {
        // Content-Type은 image/jpeg라고 주장하지만 내용은 GIF다 — 헤더를 믿지 않는다는 것의 실물 확인이다.
        mockMvc.perform(multipart("/api/photos")
                        .file(new MockMultipartFile("photos", "bag.jpg", "image/jpeg",
                                "GIF89a....".getBytes(StandardCharsets.US_ASCII))))
                .andExpect(status().isBadRequest());

        assertThat(photoStore.staged).isEmpty();
        assertThat(contract.get("rejected_status").intValue()).isEqualTo(400);
    }

    @Test
    @DisplayName("TΔ8a: 한 장이 거부되면 같은 요청의 정상 사진도 스테이징되지 않는다 — 부분 성공이 없다")
    void oneRejectedPhotoRejectsTheWholeBatch() throws Exception {
        mockMvc.perform(multipart("/api/photos")
                        .file(new MockMultipartFile("photos", "good.jpg", "image/jpeg", TestImages.jpeg()))
                        .file(new MockMultipartFile("photos", "bad.gif", "image/gif",
                                "GIF89a....".getBytes(StandardCharsets.US_ASCII))))
                .andExpect(status().isBadRequest());

        assertThat(photoStore.staged).isEmpty();
    }

    @Test
    @DisplayName("TΔ8a: PNG도 받는다 — 수용 포맷은 JPEG/PNG 둘이다")
    void acceptsPng() throws Exception {
        mockMvc.perform(multipart("/api/photos")
                        .file(new MockMultipartFile("photos", "bag.png", "image/png", TestImages.png())))
                .andExpect(status().isOk());

        assertThat(photoStore.staged).hasSize(1);
    }

    @Test
    @DisplayName("TΔ8a: 파트가 비면 400 — 올릴 것이 없는 요청이다")
    void emptyUploadIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/photos"))
                .andExpect(status().isBadRequest());

        assertThat(photoStore.staged).isEmpty();
    }

    private JsonNode load(String resource) throws IOException {
        try (InputStream in = PhotoControllerTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("계약 리소스 %s", resource).isNotNull();
            return mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        RecordingPhotoStore photoStore() {
            return new RecordingPhotoStore();
        }

        @Bean
        PhotoService photoService(RecordingPhotoStore photoStore) {
            return new PhotoService(photoStore);
        }
    }
}
