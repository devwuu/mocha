/**
 * 실 OpenAI API를 호출하는 스모크 테스트 전용 패키지(수동, 비용 발생).
 * <p>모든 테스트는 {@code @Tag("openai")}를 달아 기본 {@code test} 태스크에서 제외된다(build.gradle의 excludeTags).
 * 단언 대신 관측(출력)이 산출물인 프로브 성격이며, {@code .env.local}의 {@code OPENAI_API_KEY}
 * (없으면 환경변수)로 인증한다. 프로덕션 코드가 아닌 OpenAI SDK를 직접 사용하는 실측이므로
 * 백엔드 CLAUDE.md §5의 stub/fake 규칙 적용 대상이 아니다.
 */
package com.devwuu.mocha.smoke;
