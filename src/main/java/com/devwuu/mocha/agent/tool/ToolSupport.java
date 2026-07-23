package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.repository.NoteRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/**
 * tool 구현 역할 클래스({@link NoteLookupTools}·{@link ProposalTools}) 공용 조각 — 오류 결과 형태와
 * slug 리졸브를 한 곳에 둬 tool 간 계약을 일치시킨다.
 */
final class ToolSupport {

    private ToolSupport() {
    }

    // 오류 결과 형태는 드라이버(OpenAiChatClient.errorOutput)와 동일한 {"error": 사유} — 모델이 한 형태만 본다.
    static String errorOutput(ObjectMapper mapper, String reason) {
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
