package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.service.NoteService;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 검색·회상 축 tool 2종 — {@code list_notes}·{@code get_note}
 * (ref: specs/coffee-note-agent/data-model.md#3.1~3.2, spec FR-14; changes/0018 TΔ5, 0029 TΔ1).
 * <p>{@link ToolCallbackProvider}(façade)가 조립하는 내부 협력자라 Spring 빈이 아니다. 전부 읽기 전용이다:
 * 노트·pending을 바꾸지 않는다(AC-Δ4).
 * <p>changes/0029 TΔ1에서 {@code send_entry_card}가 사라졌다 — 기록 열람·카드 공유는 갤러리·상세 화면이
 * 가져간다(delta 0029 D-5, 카드는 TΔ9의 온디맨드 생성). 그와 함께 이 클래스의 렌더·송신 의존도 끊겼다.
 * <p>값 수준 확인(미존재 note_id)의 위반은 예외가 아니라 <b>사유를 담은 오류 결과</b>로 돌려줘
 * 에이전트가 루프 안에서 정정한다(ADR-45 — 환각 필터).
 */
class NoteLookupTools {

    private static final String LIST_NOTES_SCHEMA = """
            {"type":"object","properties":{},"required":[],"additionalProperties":false}""";

    private static final String GET_NOTE_SCHEMA = """
            {"type":"object","properties":{
              "note_id":{"type":"integer","description":"대상 노트 id — list_notes 응답의 id"}
            },"required":["note_id"],"additionalProperties":false}""";

    private final NoteService noteService;
    private final ObjectMapper mapper;

    NoteLookupTools(NoteService noteService, ObjectMapper mapper) {
        this.noteService = noteService;
        this.mapper = mapper;
    }

    // ---- list_notes (data-model §3.1) ----

    ToolCallback listNotes() {
        return new ToolCallback(
                "list_notes",
                "저장된 모든 커피 노트의 메타 목록을 돌려준다 — id·커피명·로스터리·내부 별칭(aliases)·원산지·"
                        + "공식 테이스팅 노트·최근 시음일. 기존 노트 매칭(동일성 판단)과 검색의 출발점. "
                        + "별칭은 한/영 교차·부분 표기 대조 재료다.",
                LIST_NOTES_SCHEMA,
                argumentsJson -> executeListNotes());
    }

    private String executeListNotes() {
        List<NoteSummary> notes = noteService.findAll().stream().map(NoteLookupTools::toSummary).toList();
        return mapper.writeValueAsString(Map.of("notes", notes));
    }

    private static NoteSummary toSummary(Note note) {
        // aliases는 커피명·로스터리 통합 목록으로 싣는다 — 대조 재료라 축은 구분하지 않는다(NoteSummary 계약).
        List<String> aliases = new ArrayList<>(note.aliases().coffeeName());
        aliases.addAll(note.aliases().roastery());
        return new NoteSummary(
                note.id(),
                Sourced.valueOrNull(note.coffeeName()),
                Sourced.valueOrNull(note.roastery()),
                Aliases.dedupNormalized(aliases),
                beansSummary(note.beans()),
                Sourced.valuesOrEmpty(note.officialNotes()),
                note.entries().stream().map(Entry::date).max(Comparator.naturalOrder()).orElse(null));
    }

    // list_notes 페이로드의 origin 항목은 beans 요약(설명 쉼표 나열)으로 채운다 — 매칭·검색 재료 용도라
    // 표시 형태로 충분하다(data-model §3.1, beans 대체 표현은 TΔ2a에서 재확인).
    private static String beansSummary(List<Bean> beans) {
        if (beans == null || beans.isEmpty()) {
            return null;
        }
        return beans.stream().map(b -> b.description().value()).collect(Collectors.joining(", "));
    }

    // ---- get_note (data-model §3.2) ----

    ToolCallback getNote() {
        return new ToolCallback(
                "get_note",
                "note_id로 노트 전체(모든 시음 엔트리 포함)를 돌려준다 — 상세 확인·수정 대상 검증용. "
                        + "미존재 id는 오류를 돌려준다.",
                GET_NOTE_SCHEMA,
                this::executeGetNote);
    }

    private String executeGetNote(String argumentsJson) {
        GetNoteArgs args = mapper.readValue(argumentsJson, GetNoteArgs.class);
        Optional<Note> note = ToolSupport.resolveNote(noteService, args.noteId());
        // POLICY: 미존재 note_id는 오류 반환 — 실존하지 않는 노트를 대상으로 제안이 진행되지 않게 하는
        //         환각 필터 (ref: specs/coffee-note-agent/data-model.md#3.2).
        if (note.isEmpty()) {
            return ToolSupport.errorOutput(mapper, ToolSupport.missingNoteReason(args.noteId()));
        }
        return mapper.writeValueAsString(note.get());
    }
}
