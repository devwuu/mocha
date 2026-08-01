package com.devwuu.mocha.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StringUtils;

/**
 * 0029 TΔ23: 프론트 빌드 → 정적 서빙 → SPA fallback 배관 가드.
 *
 * <p>이 테스트가 도는 것 자체가 배관의 절반을 증명한다 — {@code processResources}가 {@code frontendBuild}에
 * 의존하므로, Vite 산출물이 classpath의 {@code static/}에 얹히지 않으면 여기서 먼저 넘어진다.
 * 나머지 절반(fallback 판정)이 아래 세 케이스다.
 *
 * <p><b>API 컨트롤러는 슬라이스에서 뺀다</b>(0029 TΔ6a에서 첫 컨트롤러가 생기며 추가): 여기서 묻는 것은
 * <i>정적 리소스 리졸버가 무엇을 되돌리는가</i>이지 API 표면이 아니고, 컨트롤러를 들이면 그 협력자
 * ({@code TurnRunner} 등)를 이 테스트가 계속 따라 세워야 한다 — 컨트롤러가 늘 때마다 무관한 테스트가
 * 깨지는 결합이다. 각 API는 자기 슬라이스 테스트가 가진다({@code web/*ControllerTest}).
 */
@WebMvcTest(excludeFilters = @ComponentScan.Filter(
		type = FilterType.REGEX, pattern = "com\\.devwuu\\.mocha\\.web\\..*"))
class SpaRoutingTest {

	/** SPA 진입점. {@link WebConfig}의 같은 값과 짝이다 — 한쪽만 바꾸면 이 테스트가 잡는다. */
	private static final String SPA_ENTRY = "index.html";

	@Autowired
	MockMvc mockMvc;

	@Test
	@DisplayName("TΔ23: 루트 요청이 SPA 진입점으로 간다 — 프론트 빌드 산출물이 classpath에 얹혔다")
	void rootServesTheSpaEntryPoint() throws Exception {
		// "/"는 부트의 welcome-page 매핑이 잡아 index.html로 forward하고, 그 forward가 아래 리졸버로 다시 들어간다.
		// MockMvc는 forward를 따라가지 않으므로 여기서 볼 수 있는 것은 배달 지시까지다 — 본문 검증은
		// 리졸버가 직접 처리하는 fallback 케이스가 가져간다(다음 테스트). welcome page가 등록되려면
		// classpath에 static/index.html이 실제로 있어야 하므로, 이 단언만으로도 빌드 배관은 증명된다.
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(forwardedUrl(SPA_ENTRY));
	}

	@Test
	@DisplayName("TΔ23: 파일이 없는 경로도 SPA 진입점으로 되돌아온다 — 클라이언트 라우터가 받는다")
	void unmatchedPathFallsBackToTheSpaEntryPoint() throws Exception {
		// 새로고침·주소 직접 진입이 이 경로다. 서버에 /notes/42 라는 파일은 없고, 있어서도 안 된다.
		mockMvc.perform(get("/notes/42"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<div id=\"root\">")));
	}

	@Test
	@DisplayName("TΔ23: /api 아래 미매칭은 fallback 없이 404다 — 200 + HTML이면 원인이 가려진다")
	void unmatchedApiPathIsNotFolded() throws Exception {
		// POLICY: /api 단일 접두가 fallback 판정 기준이다(delta.md#D-4). 여기서 HTML을 돌려주면
		// 클라이언트는 JSON 파싱 오류로 넘어지고 진짜 원인(매핑 부재)이 그 뒤에 숨는다.
		mockMvc.perform(get("/api/nope"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("TΔ23: 번들 자산이 실제로 서빙된다 — index.html이 참조하는 경로가 살아 있다")
	void bundledAssetIsServed() throws Exception {
		// 자산 파일명은 콘텐츠 해시라 고정할 수 없다. index.html이 실제로 가리키는 것을 뽑아 그대로 요청한다 —
		// 파일명을 아는 유일한 정본이 index.html이고, 그래야 해시가 바뀌어도 테스트가 따라온다.
		// 본문을 얻는 통로는 fallback 경로다("/"는 forward라 MockMvc에서 본문이 비어 있다 — 위 테스트 주석).
		String indexHtml = mockMvc.perform(get("/notes/42"))
				.andReturn().getResponse().getContentAsString();
		String assetPath = firstAssetPath(indexHtml);

		mockMvc.perform(get(assetPath))
				.andExpect(status().isOk());
	}

	/** {@code index.html}에서 첫 번째 {@code /assets/...} 참조를 뽑는다. */
	private static String firstAssetPath(String indexHtml) {
		int start = indexHtml.indexOf("/assets/");
		if (start < 0) {
			throw new AssertionError("index.html이 번들 자산을 참조하지 않는다 — 프론트 빌드 산출물이 비었을 수 있다: " + indexHtml);
		}
		int end = start;
		while (end < indexHtml.length() && indexHtml.charAt(end) != '"' && indexHtml.charAt(end) != '\'') {
			end++;
		}
		String path = indexHtml.substring(start, end);
		if (!StringUtils.hasText(path)) {
			throw new AssertionError("자산 경로를 뽑지 못했다: " + indexHtml);
		}
		return path;
	}

}
