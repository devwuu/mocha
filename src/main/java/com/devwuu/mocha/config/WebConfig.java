package com.devwuu.mocha.config;

import com.devwuu.mocha.domain.Rating;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * SPA 정적 서빙 + 클라이언트 라우팅 fallback + <b>사진 아카이브 서빙</b>
 * (ref: plan.md#ADR-78, changes/0029 delta.md#D-10 ④, tasks.md TΔ5a).
 *
 * <p>클라이언트는 하나의 번들(frontend/)이고 라우팅을 자기가 한다. 그래서 {@code /notes/42} 같은 주소로
 * 새로고침·직접 진입이 들어오면 서버에는 그 경로의 파일이 없다 — 404 대신 {@code index.html}을 돌려주고
 * 경로 해석은 클라이언트 라우터에게 맡긴다.
 *
 * <p><b>{@code /api} 접두가 그 판정 기준이다</b>(D-4 경로 규약). 이 접두 아래에서는 fallback을 하지 않는다 —
 * 매핑 없는 API 경로가 200 + HTML로 답하면 클라이언트가 파싱 오류로 넘어져 원인이 가려진다. 404가 정답이다.
 * 델타가 경로를 {@code /api/...}로 고정한 값이 여기서 처음 실현된다.
 *
 * <p><b>사진은 컨트롤러가 아니라 리소스 핸들러가 돌려준다</b>(TΔ5a). 사진은 파일이고 그 서빙에 우리 로직이
 * 한 줄도 없다 — 조건부 GET(Last-Modified)·Range·경로 이스케이프 차단을 프레임워크가 이미 한다. 컨트롤러를
 * 세우면 그것들을 손으로 다시 쓰게 되고, 그 자리는 §2가 반려하는 부류다(루트 §4 right-sizing).
 * {@code PhotoStore}에 읽기 메서드를 더하지 않은 것도 같은 판단이다 — 인터페이스 경계(REVIEW.md §2)가
 * 답하는 질문은 <i>"교체 가능성이 실재하는가"</i>이고, 바이트 서빙은 그 경계가 아니라 매체 그 자체다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	/** 요청 경로에서 선행 슬래시가 제거된 형태로 들어온다(리졸버 계약). */
	private static final String API_PREFIX = "api/";

	private static final String STATIC_LOCATION = "classpath:/static/";

	private static final String SPA_ENTRY = "index.html";

	/** 사진 서빙 URL 패턴 — 접두의 소유자는 {@code web/PhotoUrl}이고 여기는 그 짝이다. */
	private static final String PHOTO_PATTERN = "/api/photos/**";

	private final Path photosDir;

	public WebConfig(@Value(RepositoryConfig.DEFAULT_DATA_DIR) String dataDir) {
		this.photosDir = Path.of(dataDir).resolve("photos");
	}

	/**
	 * 쿼리 파라미터의 {@link Rating} 변환 — 갤러리 평가 필터(TΔ5a).
	 *
	 * <p><b>{@code @JsonCreator}는 여기에 닿지 않는다</b>: 요청 <i>본문</i>은 Jackson이 읽지만 쿼리
	 * 파라미터는 {@code ConversionService}가 읽고, 그 기본 enum 변환은 <b>상수명</b>({@code PERFECT})을
	 * 기대한다. 계약이 싣는 것은 표시 문구(<i>"완전 내스타일"</i>)라 규칙을 명시하지 않으면 모든 평가
	 * 필터가 400이 된다.
	 *
	 * <p>{@link Rating#from}을 그대로 쓰는 것이 요점이다 — 4범주 판정(V-1)의 소유자를 하나로 두면 본문과
	 * 쿼리가 같은 기준을 지나고, 범주 밖 값은 예외로 튀어 Spring이 400으로 수렴시킨다.
	 */
	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(String.class, Rating.class, Rating::from);
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// 사진이 먼저다 — 아래 "/**"가 SPA fallback을 달고 있어 순서가 뒤집히면 /api 아래라 404로 떨어진다.
		// (그 리졸버가 API 경로를 index.html로 되돌리지 않는 것은 의도이고, 그래서 여기가 앞이어야 한다.)
		registry.addResourceHandler(PHOTO_PATTERN)
				.addResourceLocations(photosLocation())
				// POLICY: 사진은 불변이다 — 파일명이 아카이브 안에서 유일하고(충돌 시 -N 접미) 내용이
				//         덮이지 않는다. 그래서 재검증 왕복 자체를 없앤다: 폰에서 갤러리를 훑을 때마다
				//         썸네일 수십 건의 304를 왕복하지 않는다. cachePrivate인 것은 개인 데이터라
				//         중간 캐시에 남기지 않기 위해서다(루트 CLAUDE.md §5).
				.setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
				.resourceChain(true)
				// 아카이브 뿌리 밖으로 나가는 경로를 거부한다 — LocalPhotoStore가 삭제 측에서 지키는
				// 규약(V-4 photos/ 접두)을 읽기 측에서 프레임워크가 지는 자리다.
				.addResolver(new PathResourceResolver());

		// POLICY: 정적 매핑을 이 클래스가 단독 소유한다 — application.yaml의
		// spring.web.resources.add-mappings=false가 부트 기본 매핑을 끈다. 기본 매핑을 켠 채로 두면
		// 같은 "/**" 패턴이 둘 등록되어 어느 쪽이 이기는지가 등록 순서에 달리고, 그것은 관측하기 어렵다.
		registry.addResourceHandler("/**")
				.addResourceLocations(STATIC_LOCATION)
				.resourceChain(true)
				.addResolver(new SpaFallbackResourceResolver());
	}

	/**
	 * 아카이브 디렉터리의 {@code file:} 위치 — 반드시 {@code /}로 끝나야 한다.
	 *
	 * <p>{@code Path.toUri()}는 <b>디렉터리가 실제로 있을 때만</b> 후행 슬래시를 붙인다. 사진을 한 장도
	 * 저장하지 않은 새 설치에서는 그 디렉터리가 없어 위치가 파일로 해석되고, 그때 모든 사진 요청이 404가
	 * 된다 — 첫 저장 전까지만 나타났다 사라지는 종류의 고장이라 손으로 붙인다.
	 */
	private String photosLocation() {
		String uri = photosDir.toAbsolutePath().normalize().toUri().toString();
		return uri.endsWith("/") ? uri : uri + "/";
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
