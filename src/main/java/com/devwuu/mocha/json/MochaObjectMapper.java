package com.devwuu.mocha.json;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도메인 JSON 직렬화 설정의 단일 출처.
 * <p>NoteRepository/PendingStore(파일 I/O)와 테스트가 같은 매퍼 규칙을 쓰도록 여기서만 구성한다.
 * <ul>
 *   <li>snake_case — data-model의 JSON 필드명(coffee_name, roast_level, my_taste ...)과 일치</li>
 *   <li>날짜/시각은 ISO-8601 문자열 — Jackson 3 기본값</li>
 * </ul>
 * <p>Spring Boot 4 기본인 Jackson 3(tools.jackson) 사용 — java.time 지원은 databind에 내장·자동 등록.
 */
public final class MochaObjectMapper {

    private MochaObjectMapper() {
    }

    public static ObjectMapper create() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // OffsetDateTime 원 오프셋(예: +09:00) 보존 — UTC로 정규화하지 않음(왕복 동일성).
                .configure(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
                .build();
    }
}
