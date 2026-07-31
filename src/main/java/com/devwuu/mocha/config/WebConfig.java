package com.devwuu.mocha.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * SPA 정적 서빙 + 클라이언트 라우팅 fallback (ref: plan.md#ADR-78, changes/0029 delta.md#D-10 ④).
 *
 * <p>클라이언트는 하나의 번들(frontend/)이고 라우팅을 자기가 한다. 그래서 {@code /notes/42} 같은 주소로
 * 새로고침·직접 진입이 들어오면 서버에는 그 경로의 파일이 없다 — 404 대신 {@code index.html}을 돌려주고
 * 경로 해석은 클라이언트 라우터에게 맡긴다.
 *
 * <p><b>{@code /api} 접두가 그 판정 기준이다</b>(D-4 경로 규약). 이 접두 아래에서는 fallback을 하지 않는다 —
 * 매핑 없는 API 경로가 200 + HTML로 답하면 클라이언트가 파싱 오류로 넘어져 원인이 가려진다. 404가 정답이다.
 * 델타가 경로를 {@code /api/...}로 고정한 값이 여기서 처음 실현된다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	/** 요청 경로에서 선행 슬래시가 제거된 형태로 들어온다(리졸버 계약). */
	private static final String API_PREFIX = "api/";

	private static final String STATIC_LOCATION = "classpath:/static/";

	private static final String SPA_ENTRY = "index.html";

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// POLICY: 정적 매핑을 이 클래스가 단독 소유한다 — application.yaml의
		// spring.web.resources.add-mappings=false가 부트 기본 매핑을 끈다. 기본 매핑을 켠 채로 두면
		// 같은 "/**" 패턴이 둘 등록되어 어느 쪽이 이기는지가 등록 순서에 달리고, 그것은 관측하기 어렵다.
		registry.addResourceHandler("/**")
				.addResourceLocations(STATIC_LOCATION)
				.resourceChain(true)
				.addResolver(new SpaFallbackResourceResolver());
	}

	/** 요청한 파일이 없으면 SPA 진입점으로 되돌린다. 단 {@code /api} 아래는 되돌리지 않는다. */
	static final class SpaFallbackResourceResolver extends PathResourceResolver {

		@Override
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			Resource requested = super.getResource(resourcePath, location);
			if (requested != null) {
				return requested;
			}
			if (resourcePath.startsWith(API_PREFIX)) {
				return null;
			}
			return super.getResource(SPA_ENTRY, location);
		}
	}

}
