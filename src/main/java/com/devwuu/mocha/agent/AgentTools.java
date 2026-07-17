package com.devwuu.mocha.agent;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.slack.SlackResponder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 에이전트 function tool 구현 소유 — 도메인 협력자를 주입받아 tool 정의(스키마)+실행기를 조립한다
 * (ref: specs/coffee-note-agent/plan.md §3 AgentTools, #ADR-44/#ADR-45).
 * <p>이 클래스(TΔ5)는 읽기·카드 3종({@code list_notes}·{@code get_note}·{@code send_entry_card})을 담고,
 * 제안 tool 2종({@code propose_record}·{@code propose_edit})은 TΔ6에서 추가한다. 내장 {@code web_search}는
 * 드라이버({@link OpenAiAgentClient})가 장착하므로 여기 없다.
 * <p>strict schema(전 필드 required·additionalProperties=false)가 인자 <b>형태</b>를 보장하고, 값 수준
 * 확인(미존재 slug·날짜 형식)은 여기서 결정론으로 한다 — 위반은 예외가 아니라 <b>사유를 담은 오류 결과</b>로
 * 돌려줘 에이전트가 루프 안에서 정정한다(ADR-45, findings-TΔ0 §SDK).
 */
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    // 카드 재전송 캡션 — 모카 톤 유지(delta UNCHANGED). 구 검색 세션 종료 안내("됐어")는 세션 소멸로 뺐다.
    static final String CARD_CAPTION = "이 기록이에요 멍! 🐾";

    private static final String LIST_NOTES_SCHEMA = """
            {"type":"object","properties":{},"required":[],"additionalProperties":false}""";

    private static final String GET_NOTE_SCHEMA = """
            {"type":"object","properties":{
              "slug":{"type":"string","description":"대상 노트 slug — list_notes 응답의 slug"}
            },"required":["slug"],"additionalProperties":false}""";

    private static final String SEND_ENTRY_CARD_SCHEMA = """
            {"type":"object","properties":{
              "slug":{"type":"string","description":"대상 노트 slug — list_notes 응답의 slug"},
              "date":{"type":"string","description":"대상 시음 엔트리 날짜(YYYY-MM-DD) — get_note 응답의 entries[].date"}
            },"required":["slug","date"],"additionalProperties":false}""";

    private final NoteRepository noteRepository;
    private final NoteRenderer noteRenderer;
    private final SlackResponder responder;
    private final Path artifactDir;
    private final ObjectMapper mapper;

    public AgentTools(NoteRepository noteRepository, NoteRenderer noteRenderer, SlackResponder responder,
                      Path artifactDir, ObjectMapper mapper) {
        this.noteRepository = noteRepository;
        this.noteRenderer = noteRenderer;
        this.responder = responder;
        this.artifactDir = artifactDir;
        this.mapper = mapper;
    }

    /**
     * 에이전트 턴 1회에 장착할 tool 목록 — {@code send_entry_card}가 카드를 배달할 채널을 턴마다 바인딩한다.
     */
    public List<AgentTool> forTurn(String channelId) {
        return List.of(listNotesTool(), getNoteTool(), sendEntryCardTool(channelId));
    }

    // ---- list_notes (data-model §3.1) ----

    private AgentTool listNotesTool() {
        return new AgentTool(
                "list_notes",
                "저장된 모든 커피 노트의 메타 목록을 돌려준다 — slug·커피명·로스터리·내부 별칭(aliases)·원산지·"
                        + "공식 테이스팅 노트·최근 시음일. 기존 노트 매칭(동일성 판단)과 검색의 출발점. "
                        + "별칭은 한/영 교차·부분 표기 대조 재료다.",
                LIST_NOTES_SCHEMA,
                argumentsJson -> executeListNotes());
    }

    private String executeListNotes() {
        List<NoteSummary> notes = noteRepository.findAll().stream().map(AgentTools::toSummary).toList();
        return mapper.writeValueAsString(Map.of("notes", notes));
    }

    private static NoteSummary toSummary(Note note) {
        // aliases는 커피명·로스터리 통합 목록으로 싣는다 — 대조 재료라 축은 구분하지 않는다(NoteSummary 계약).
        List<String> aliases = new ArrayList<>(note.aliases().coffeeName());
        aliases.addAll(note.aliases().roastery());
        return new NoteSummary(
                note.slug(),
                valueOf(note.coffeeName()),
                valueOf(note.roastery()),
                Aliases.dedupNormalized(aliases),
                valueOf(note.origin()),
                note.officialNotes() == null ? List.of() : note.officialNotes().value(),
                note.entries().stream().map(Entry::date).max(Comparator.naturalOrder()).orElse(null));
    }

    private static String valueOf(Sourced<String> sourced) {
        return sourced == null ? null : sourced.value();
    }

    // ---- get_note (data-model §3.2) ----

    private AgentTool getNoteTool() {
        return new AgentTool(
                "get_note",
                "slug로 노트 전체(모든 시음 엔트리 포함)를 돌려준다 — 상세 확인·수정 대상 검증용. "
                        + "미존재 slug는 오류를 돌려준다.",
                GET_NOTE_SCHEMA,
                this::executeGetNote);
    }

    private String executeGetNote(String argumentsJson) {
        GetNoteArgs args = mapper.readValue(argumentsJson, GetNoteArgs.class);
        Optional<Note> note = resolveNote(args.slug());
        // POLICY: 미존재 slug는 오류 반환 — 실존하지 않는 노트를 대상으로 제안이 진행되지 않게 하는
        //         환각 필터 (ref: specs/coffee-note-agent/data-model.md#3.2).
        if (note.isEmpty()) {
            return errorOutput(missingNoteReason(args.slug()));
        }
        return mapper.writeValueAsString(note.get());
    }

    // ---- send_entry_card (data-model §3.5, FR-20) ----

    private AgentTool sendEntryCardTool(String channelId) {
        return new AgentTool(
                "send_entry_card",
                "기존 시음 엔트리의 카드 이미지를 사용자 채널에 전송한다 — 기존 기록을 찾는 발화의 답은 이 카드다. "
                        + "전송까지 이 tool이 끝내므로 최종 텍스트에 카드 내용을 반복할 필요 없다.",
                SEND_ENTRY_CARD_SCHEMA,
                argumentsJson -> executeSendEntryCard(channelId, argumentsJson));
    }

    private String executeSendEntryCard(String channelId, String argumentsJson) {
        SendEntryCardArgs args = mapper.readValue(argumentsJson, SendEntryCardArgs.class);
        Optional<Note> note = resolveNote(args.slug());
        if (note.isEmpty()) {
            return errorOutput(missingNoteReason(args.slug()));
        }
        LocalDate date;
        try {
            date = LocalDate.parse(args.date() == null ? "" : args.date().strip());
        } catch (DateTimeParseException e) {
            return errorOutput("date '" + args.date() + "'는 날짜 형식이 아니다 — YYYY-MM-DD로 보내라.");
        }
        // 환각 필터(get_note 미존재 오류와 같은 정신): 실존하지 않는 엔트리의 카드를 굽지 않는다.
        LocalDate target = date;
        if (note.get().entries().stream().noneMatch(entry -> target.equals(entry.date()))) {
            return errorOutput("노트 '" + note.get().slug() + "'에는 " + date
                    + " 시음 엔트리가 없다 — get_note로 실제 엔트리 날짜를 확인해라.");
        }

        // POLICY: 검색 응답은 새 파생물을 최소화한다 — 기존 카드 JPG 재사용, 파일 부재 시에만 그 엔트리 1장
        //         증분 렌더 (ref: specs/coffee-note-agent/data-model.md#3.5, 구 ADR-25 정신 승계).
        Path card = artifactDir.resolve("cards").resolve(note.get().slug()).resolve(date + ".jpg");
        boolean reused = Files.exists(card);
        if (!reused) {
            card = noteRenderer.renderEntryCard(note.get().slug(), date);
        }
        responder.postImage(channelId, card, CARD_CAPTION);
        log.info("검색 카드 재전송: slug={} date={} reused={}", note.get().slug(), date, reused);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", true);
        result.put("slug", note.get().slug());
        result.put("date", date.toString());
        return mapper.writeValueAsString(result);
    }

    // ---- 공통 ----

    private Optional<Note> resolveNote(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return noteRepository.findBySlug(slug.strip());
    }

    private static String missingNoteReason(String slug) {
        return "노트 '" + slug + "'가 없다 — list_notes로 실제 slug를 확인해라.";
    }

    // 오류 결과 형태는 드라이버(OpenAiAgentClient.errorOutput)와 동일한 {"error": 사유} — 모델이 한 형태만 본다.
    private String errorOutput(String reason) {
        return mapper.writeValueAsString(Map.of("error", reason));
    }
}
