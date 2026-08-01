package com.devwuu.mocha.web;

import com.devwuu.mocha.domain.NoteCursor;
import com.devwuu.mocha.json.MochaObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

/**
 * 페이징 커서의 <b>전송 표현</b> — {@link NoteCursor} ↔ 불투명 문자열
 * (ref: changes/0029 tasks.md TΔ5a·TΔ12, 계약 {@code contract/note-list.contract.json}).
 *
 * <p><b>왜 불투명한가</b>: 클라이언트는 커서를 만들지도 해석하지도 않고 받은 값을 그대로 되싣는다. 그래야
 * 정렬 축이 바뀌어도 클라이언트가 따라 바뀌지 않는다. base64가 그 계약을 <i>보이는 형태로</i> 만든다 —
 * 값이 그대로 읽히면 클라이언트가 파싱해 쓰고 싶어지고, 그 순간 서버가 축을 못 바꾼다.
 *
 * <p>이 변환이 전송 계층에 있는 것은 백엔드 CLAUDE.md §2가 정한 자리다(컨트롤러 = 파싱·위임·응답 변환).
 * <b>정렬 축</b>은 질의가 소유하고 <b>커서가 실어 나르는 값</b>은 계약이며, 그 값을 문자열로 만드는 규칙만
 * 여기 있다.
 */
final class NoteCursorCodec {

    // 도메인 JSON 규칙(snake_case)과 무관한 내부 표현이라 주입이 아니라 자체 매퍼다 — 키가 한 글자
    // (d·i)라 명명 전략이 닿을 자리가 없고, 이 형식은 계약 파일의 예시와 짝이다.
    private static final JsonMapper MAPPER = MochaObjectMapper.create();

    private NoteCursorCodec() {
    }

    /** 페이로드 — 키가 짧은 것은 커서가 URL 쿼리에 실리기 때문이다. {@code d}는 날짜, {@code i}는 note_id. */
    private record Payload(String d, long i) {
    }

    static String encode(NoteCursor cursor) {
        if (cursor == null) {
            return null;
        }
        LocalDate date = cursor.latestDate();
        String json = MAPPER.writeValueAsString(
                new Payload(date == null ? null : date.toString(), cursor.noteId()));
        // 패딩 없는 base64url — '='·'+'·'/'가 쿼리 문자열에서 이스케이프를 요구하지 않게 한다.
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 커서 문자열을 값으로 — 빈 값이면 {@code null}(첫 페이지).
     *
     * <p>POLICY: 해석할 수 없는 커서는 <b>거부</b>한다(호출부가 400으로 수렴). 조용히 첫 페이지로 되돌리면
     * 무한 스크롤이 처음으로 되감기며 <b>같은 노트를 목록 뒤에 다시 쌓는다</b> — 사용자에게는 데이터가
     * 이상해 보이고 원인은 보이지 않는다 (ref: changes/0029 tasks.md TΔ5a).
     *
     * @throws IllegalArgumentException 형식이 깨진 커서.
     */
    static NoteCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            Payload payload = MAPPER.readValue(json, Payload.class);
            return new NoteCursor(payload.d() == null ? null : LocalDate.parse(payload.d()), payload.i());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("해석할 수 없는 커서: " + encoded, e);
        }
    }
}
