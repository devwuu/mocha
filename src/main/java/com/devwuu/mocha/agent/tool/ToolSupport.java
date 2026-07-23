package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.repository.NoteRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/**
 * 모델 대면 tool 계약 공용 조각 — tool 구현 역할 클래스({@code NoteLookupTools}·{@code ProposalTools})와
 * 루프 드라이버({@code OpenAiChatClient})가 공유하는 오류 결과 형태·slug 리졸브를 한 곳에 둬 계약을 일치시킨다.
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

    static String missingNoteReason(String slug) {
        return "노트 '" + slug + "'가 없다 — list_notes로 실제 slug를 확인해라.";
    }

    static Optional<Note> resolveNote(NoteRepository noteRepository, String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return noteRepository.findBySlug(slug.strip());
    }
}
