package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.repository.NoteRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/**
 * 모델 대면 tool 계약 공용 조각 — tool 구현 역할 클래스({@code NoteLookupTools}·{@code ProposalTools})와
 * 루프 드라이버({@code OpenAiChatClient})가 공유하는 오류 결과 형태·노트 리졸브를 한 곳에 둬 계약을 일치시킨다.
 *
 * <p>POLICY: 모델 대면 오류 결과 {@code {"error": 사유}}의 프로덕션 정의는 {@link #errorOutput} 1곳뿐이다
 * — 같은 형태의 두 번째 정의를 만들지 않는다 (ref: specs/coffee-note-agent/plan.md#ADR-67).
 */
public final class ToolSupport {

    private ToolSupport() {
    }

    // 오류 결과 형태 {"error": 사유} 단일 지점 — tool 구현과 드라이버가 모두 여기로 수렴, 모델이 한 형태만 본다(ADR-67).
    public static String errorOutput(ObjectMapper mapper, String reason) {
        return mapper.writeValueAsString(Map.of("error", reason));
    }

    static String missingNoteReason(String rawNoteId) {
        return "노트 '" + rawNoteId + "'가 없다 — list_notes로 실제 id를 확인해라.";
    }

    /** 검증을 통과해 이미 Long인 id의 변형 — 문구는 한 곳(위)에서만 만든다. */
    static String missingNoteReason(long noteId) {
        return missingNoteReason(String.valueOf(noteId));
    }

    // POLICY: 모델이 넘긴 노트 식별자의 실패는 갈래를 만들지 않는다 — 비숫자 문자열(파싱 실패)도 미존재
    //         id와 같은 부류의 오류 결과로 수렴시킨다. slug 폐기로 새로 생긴 실패 경로인데, 예외로 새면
    //         에이전트가 루프 안에서 list_notes로 정정할 근거를 못 받는다
    //         (ref: plan.md#ADR-45 환각 필터, changes/0028 TΔ0b §4 E-6).
    static Optional<Note> resolveNote(NoteRepository noteRepository, String rawNoteId) {
        if (rawNoteId == null || rawNoteId.isBlank()) {
            return Optional.empty();
        }
        try {
            return noteRepository.findById(Long.parseLong(rawNoteId.strip()));
        } catch (NumberFormatException notAnId) {
            return Optional.empty();
        }
    }
}
