package com.devwuu.mocha.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 0029 TΔ14: 홈 화면 추가(PWA 셸) 가드.
 *
 * <p><b>왜 테스트가 있는가</b> — 이 셸의 고장은 조용하다. manifest나 아이콘 파일 하나가 이름이 바뀌거나
 * 번들에서 빠져도 <i>앱은 정상으로 보인다</i>. 드러나는 자리는 폰에서 홈 화면에 추가한 순간이고(iOS는
 * {@code apple-touch-icon}이 없으면 화면 캡처를 아이콘으로 쓴다) 그때는 이미 실사용 중이다.
 *
 * <p>아이콘 목록을 여기 나열하지 않고 <b>manifest에서 읽어 요청한다</b>. 목록을 두 벌로 두면 manifest에
 * 아이콘을 더한 사람이 테스트도 고쳐야 하고, 안 고치면 테스트가 그린인 채로 404가 남는다.
 *
 * <p>슬라이스에서 API 컨트롤러를 빼는 이유는 {@link SpaRoutingTest}와 같다 — 묻는 것이 정적 리소스이지
 * API 표면이 아니다.
 */
@WebMvcTest(excludeFilters = @ComponentScan.Filter(
		type = FilterType.REGEX, pattern = "com\\.devwuu\\.mocha\\.web\\..*"))
class PwaShellTest {

	/** manifest 경로. {@code index.html}의 {@code <link rel="manifest">}와 짝이고 아래 첫 테스트가 그것을 확인한다. */
	private static final String MANIFEST = "/manifest.json";

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	MockMvc mockMvc;

	@Test
	@DisplayName("TΔ14: index.html이 manifest와 apple-touch-icon을 선언한다 — 홈 화면 추가의 두 진입점")
	void entryPointDeclaresTheInstallSurface() throws Exception {
		// 본문을 얻는 통로는 fallback 경로다("/"는 forward라 MockMvc에서 본문이 비어 있다 — SpaRoutingTest 주석).
		String indexHtml = mockMvc.perform(get("/notes/42"))
				.andReturn().getResponse().getContentAsString();

		assertThat(indexHtml)
				.as("manifest 링크가 없으면 브라우저가 설치 대상으로 보지 않는다")
				.contains("rel=\"manifest\"")
				.contains(MANIFEST);
		assertThat(indexHtml)
				.as("iOS는 manifest 아이콘을 홈 화면에 쓰지 않는다 — 이 링크가 아이콘의 유일한 출처다")
				.contains("rel=\"apple-touch-icon\"");
	}

	@Test
	@DisplayName("TΔ14: manifest가 서빙되고 홈 화면 추가에 필요한 값을 싣는다")
	void manifestIsServedWithTheInstallFields() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(get(MANIFEST))
				.andExpect(status().isOk())
				.andReturn().getResponse();

		// POLICY: 파일 이름이 `.json`인 근거가 이 단언이다 — `.webmanifest`로 두면 Spring의 MIME 표에
		//         그 확장자가 없어 application/octet-stream으로 나간다(실측). 이름을 되돌리면 여기가 잡는다.
		assertThat(response.getContentType()).startsWith("application/json");

		JsonNode manifest = JSON.readTree(response.getContentAsByteArray());
		assertThat(manifest.path("name").asText()).isEqualTo("모카");
		// standalone이 아니면 홈 화면에서 열어도 브라우저 크롬이 붙는다 — 이 task가 만드는 것이 그 차이다.
		assertThat(manifest.path("display").asText()).isEqualTo("standalone");
		assertThat(manifest.path("start_url").asText()).isEqualTo("/");
		// 스플래시 배경과 상태바 색. index.html의 theme-color meta와 같은 값이어야 한다(둘 다 --shell).
		assertThat(manifest.path("theme_color").asText()).isEqualTo("#d9cfc4");
		assertThat(manifest.path("background_color").asText()).isEqualTo("#d9cfc4");
	}

	@Test
	@DisplayName("TΔ14: manifest가 가리키는 아이콘이 전부 실제로 서빙된다 — 하나라도 빠지면 조용히 실패한다")
	void everyIconTheManifestPointsAtIsServed() throws Exception {
		byte[] body = mockMvc.perform(get(MANIFEST))
				.andReturn().getResponse().getContentAsByteArray();
		JsonNode icons = JSON.readTree(body).path("icons");
		assertThat(icons).as("아이콘 없는 manifest는 설치되지 않는다").isNotEmpty();

		for (JsonNode icon : icons) {
			String src = icon.path("src").asText();
			mockMvc.perform(get(src))
					.andExpect(status().isOk());
		}
		// maskable 판본이 따로 있어야 안드로이드 런처가 마스코트를 잘라내지 않는다(안전 영역 80%).
		assertThat(icons.findValuesAsText("purpose")).contains("maskable");
	}

	@Test
	@DisplayName("TΔ14: apple-touch-icon이 서빙된다 — 없으면 iOS 홈 화면 아이콘이 화면 캡처가 된다")
	void appleTouchIconIsServed() throws Exception {
		mockMvc.perform(get("/apple-touch-icon.png"))
				.andExpect(status().isOk());
	}

}
