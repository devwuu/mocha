package com.devwuu.mocha.web;

import com.devwuu.mocha.SingleUser;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.NoteCursor;
import com.devwuu.mocha.domain.NoteFilter;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.service.NoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 노트 REST 표면 — 쓰기 넷과 읽기 셋
 * (ref: changes/0029 tasks.md, plan.md#ADR-3·#ADR-4; 계약 정본은 {@code src/test/resources/contract/}의
 * 같은 이름 파일들이다).
 * <ul>
 *   <li>{@code POST /api/notes} — 폼 확정 저장(TΔ6b, {@code note-commit})</li>
 *   <li>{@code GET /api/notes/candidates} — 매칭 후보 검색(TΔ7, {@code note-candidates})</li>
 *   <li>{@code GET /api/notes} — 갤러리 목록(TΔ5a·TΔ12, {@code note-list})</li>
 *   <li>{@code GET /api/notes/&#123;id&#125;} — 상세(TΔ5a·TΔ13a, {@code note-detail})</li>
 *   <li>{@code PATCH /api/notes/&#123;id&#125;} — 메타 수정(TΔ5b-3·TΔ13b, {@code note-update})</li>
 *   <li>{@code PATCH /api/notes/&#123;id&#125;/tasting-days/&#123;date&#125;} — 시음일 수정·날짜 이동(같은 계약)</li>
 *   <li>{@code DELETE /api/notes/&#123;id&#125;} — 노트 삭제(같은 계약)</li>
 * </ul>
 *
 * <p><b>PATCH가 둘인 것은 TΔ4a의 분리를 그대로 노출한 것이다</b> — 구 {@code applyEdit}은 "노트 메타 +
 * 대상 시음일 1건"을 한 번에 받아, 로스터리만 고쳐도 그 시음일의 회차 행이 이유 없이 재발급됐다. 여기서
 * 다시 합치면 그 계약이 되살아난다.
 *
 * <p><b>세 쓰기가 실패를 갈라 말한다</b>: {@code POST}의 대상 소실은 <b>409</b>(폼은 유효한데 병합할
 * 노트가 그 사이 사라졌다 — 상태 충돌)이고, {@code PATCH}·{@code DELETE}의 그것은 <b>404</b>다(URL이
 * 가리키는 자원이 없다). 같은 예외가 두 뜻을 지는 것이 아니라 <b>자리가 뜻을 정한다</b>.
 *
 * <p><b>읽기 셋이 같은 노트를 서로 다른 깊이로 말한다</b>: 후보와 목록은 노트를 <i>고르는</i> 자리라
 * 납작한 사영이고(3단 중첩을 한 줄도 쓰지 않는다), 상세는 <i>읽는</i> 자리라 전문이다. 세 계약이 갈린 것이
 * 아니라 화면이 셋이고 각자 필요한 만큼만 싣는다.
 *
 * <p><b>TΔ4에서 열린 기록 공백이 여기서 닫힌다.</b> pending이 사라지며 Slack [저장]이 성립하지 않게 됐고,
 * 그 뒤로 저장 경로가 아예 없었다. 이 컨트롤러가 그 자리를 대신한다.
 *
 * <p><b>오케스트레이션을 여기서 다시 짜지 않는다</b>(백엔드 CLAUDE.md §2): 별칭 생성 → 저장의 순서와
 * 신규/기존 분기는 {@link NoteService#commit}이 소유하고, 여기서 하는 일은 <b>파싱·위임·응답 변환</b>과
 * 그 위의 접힘 한 줄이다. TΔ4a가 만든 <i>"부를 수 있는 유스케이스"</i>의 프로덕션 호출부가 이 한 줄이다.
 *
 * <p><b>커밋 접힘의 배선 지점</b>(TΔ4 이월 해소): {@code FoldTrigger.SAVE_COMMIT}은 Slack 버튼 핸들러와
 * 함께 무배선이 됐고 그 사이 트랜스크립트는 TTL로만 접혔다. 접힘을 <b>유스케이스가 아니라 전송 계층이</b>
 * 부르는 것은 구 라우터({@code onAction})의 배치를 그대로 승계한 것이다 — 대화 문맥은 저장의 관심사가
 * 아니라 <i>입구</i>의 관심사이고, {@link NoteService}가 트랜스크립트를 알면 저장이 대화에 묶인다.
 *
 * <p>POLICY: 저장이 실패하면 접지 않는다 — 폼도 문맥도 사용자 쪽에 남아 재시도가 성립해야 한다
 * (ref: plan.md#ADR-3 <i>"저장은 확인 이후에만"</i>의 대칭).
 *
 * <p><b>사진 확정은 이 컨트롤러에 없다</b>(TΔ8b) — 구 {@code SlackCommitHandler}가 커밋 뒤에 하던 일이고,
 * 그 자리는 {@code NoteService.commit} 안이다. 예고대로 <b>시그니처가 바뀌지 않아 이 코드는 한 줄도
 * 고쳐지지 않았다</b>: 스테이징 소유 키가 고정 단일 사용자이고 폴더 접미·날짜는 저장된 노트에서 나오므로
 * 전송 계층이 넘길 것이 없다.
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private static final Logger log = LoggerFactory.getLogger(NoteController.class);

    private final NoteService noteService;
    private final FoldingChatMemory transcript;

    public NoteController(NoteService noteService, FoldingChatMemory transcript) {
        this.noteService = noteService;
        this.transcript = transcript;
    }

    /**
     * 확정된 폼을 저장한다. 본문은 턴 응답의 draft와 <b>같은 형태</b>다 — 방향만 다른 같은 값이라
     * 클라이언트가 받은 것을 그대로 되돌려 보낸다({@link DraftBody}).
     *
     * <p>실패는 상태 코드로 갈린다(TΔ6a와 같은 규약 — 안내 문구의 소유자는 화면이다):
     * 저장할 시음이 없으면 400, 병합 대상 노트가 그 사이 사라졌으면 409, 그 밖은 500.
     */
    @PostMapping
    public ResponseEntity<Response> commit(@RequestBody DraftBody request) {
        // match가 없으면 신규/기존 판정이 없다는 뜻이고, 관대하게 받으면 "신규인데 별칭 없이 저장"으로
        // 조용히 수렴한다(노트당 평생 1회인 생성 콜을 영영 놓친다, ADR-37). 받지 않는다.
        if (request == null || request.note() == null || request.match() == null) {
            return ResponseEntity.badRequest().build();
        }
        // POLICY: 수정 모드 폼은 이 경로로 저장하지 않는다 — 커밋은 회차를 **더하는** 일이라
        //         match=edit이 여기 오면 "고치려던 것이 회차 추가로 저장되는" 조합이 된다.
        //         수정의 저장 경로는 PATCH /api/notes/{id}/tasting-days/{date}다
        //         (ref: changes/0029 delta.md#D-14, tasks.md TΔ28b·TΔ29a).
        if (request.match().type() == MatchInfo.MatchType.EDIT) {
            log.warn("저장 거부(경로 불일치): match=edit은 시음일 PATCH로 저장한다 — noteId={}",
                    request.match().noteId());
            return ResponseEntity.badRequest().build();
        }
        try {
            // POLICY: 사용자 [저장] 확정을 거친 요청만 여기 온다 — 이 호출이 그 확정이다
            //         (ref: plan.md#ADR-3, spec AC-4).
            Note saved = noteService.commit(request.note().toNote(), request.match());
            // 커밋 접힘 — 확정된 작업의 문맥은 버린다(ADR-46 커밋 규칙, TΔ3 개정 2규칙 중 하나).
            transcript.clear(SingleUser.ID, FoldingChatMemory.FoldTrigger.SAVE_COMMIT);
            return ResponseEntity.ok(new Response(saved.id()));
        } catch (IllegalArgumentException e) {
            // 저장할 시음일이 없다 — 클라이언트가 보낸 것이 폼이 아니다(NoteService 입력 검증).
            log.warn("저장 거부(요청 결손): {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            // 병합 대상 노트가 턴과 저장 사이에 사라졌다 — 서버 고장이 아니라 상태 충돌이다.
            log.warn("저장 거부(대상 소실): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (Exception e) {
            // POLICY: 실패를 삼키지 않는다 — 접히지 않았으므로 폼·문맥은 사용자 쪽에 그대로 남는다
            //         (ref: plan.md#ADR-48). 원문은 개인 데이터라 logs/ 비커밋 규칙이 적용된다(NFR-7).
            log.warn("저장 실패(폼 유지): coffeeName={}", coffeeNameOf(request), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 매칭 후보 검색 — 변경 시트가 열릴 때와 검색어가 바뀔 때 부른다(TΔ7, 계약 TΔ11).
     *
     * <p><b>후보 없음은 오류가 아니라 빈 목록이다</b> — 그때 사용자의 다음 행동이 [새 노트로 등록]이고,
     * 그 버튼은 시트 안에 있다. 404로 답하면 화면이 그 자리를 그리지 못한다.
     *
     * <p>{@code q}는 선택이다. 빈 값이면 전건을 계약 정렬로 돌려준다 — 커피명이 아직 안 뽑힌 상태에서도
     * 시트가 쓸모 있어야 한다는 것이 화면이 정한 계약이다(TΔ11).
     *
     * <p>정렬·대조 규칙을 여기서 짜지 않는다(백엔드 CLAUDE.md §2): 무엇을 어떤 순서로 돌려줄지는
     * {@code NoteEntityRepositoryCustom#findCandidates}가 소유하고, 이 메서드가 하는 일은 파싱·위임·
     * 응답 변환뿐이다.
     */
    @GetMapping("/candidates")
    public Candidates candidates(@RequestParam(name = "q", required = false) String q) {
        return new Candidates(noteService.findCandidates(q));
    }

    /**
     * 갤러리 목록 — 검색어 + 필터 4축 + 커서 페이징 (ref: TΔ5a·TΔ12, AC-4).
     *
     * <p>파라미터는 <b>전부 선택</b>이다. 빈 값은 클라이언트가 키째 빼고 보내므로(계약) 여기서
     * <i>"빈 문자열로 필터"</i>와 <i>"필터 없음"</i>을 가르지 않는다 — 그 구분은 어느 쪽에도 쓸모가 없다.
     * 다중 축은 같은 키를 반복해 실린다({@code ?roastery=프릳츠&roastery=모모스}) — 쉼표로 이으면 값 안의
     * 쉼표(<i>"커피 리브레, 성수"</i>)와 구분자가 충돌한다.
     *
     * <p><b>평가는 {@link Rating}으로 받는다</b> — 4범주 밖의 값은 역직렬화가 거부하고(V-1) 그 결과가 400이다.
     * 문자열로 받아 아래에서 대조하면 오타가 <i>"아무것도 안 걸리는 필터"</i>로 조용히 수렴한다.
     *
     * <p>결과 없음은 오류가 아니라 빈 목록이다 — 필터를 좁혀 0건이 되는 것은 정상 조작이고, 그때 화면이
     * <i>"조건에 맞는 기록이 없어요"</i>를 그린다(TΔ11 후보 시트와 같은 POLICY).
     *
     * <p>필터·정렬·페이징 규칙을 여기서 짜지 않는다(백엔드 CLAUDE.md §2) — 파싱·위임·응답 변환뿐이고,
     * 파싱이 실질을 갖는 유일한 자리가 <b>불투명 커서</b>다({@link NoteCursorCodec}).
     */
    @GetMapping
    public ResponseEntity<NoteListBody> notes(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "roastery", required = false) List<String> roastery,
            @RequestParam(name = "process", required = false) List<String> process,
            @RequestParam(name = "origin", required = false) String origin,
            @RequestParam(name = "rating", required = false) List<Rating> rating,
            @RequestParam(name = "cursor", required = false) String cursor) {
        NoteCursor from;
        try {
            from = NoteCursorCodec.decode(cursor);
        } catch (IllegalArgumentException e) {
            // 서버가 발급한 값만 되돌아오는 자리라 깨진 커서는 클라이언트 오류다 — 조용히 첫 페이지로
            // 되돌리면 무한 스크롤이 되감기며 같은 노트를 목록 뒤에 다시 쌓는다(NoteCursorCodec POLICY).
            log.warn("목록 조회 거부(커서 해석 실패): {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        NoteFilter filter = new NoteFilter(q, roastery, process, origin, rating);
        return ResponseEntity.ok(NoteListBody.of(noteService.findNotes(filter, from)));
    }

    /**
     * 노트 상세 — 전문 + 날짜별 사진 (ref: TΔ5a·TΔ13a).
     *
     * <p><b>없는 id는 404다</b> — 삭제된 노트를 텅 빈 상세로 그리지 않는다. 그 화면은 <i>"기록은 있는데
     * 아무것도 안 적혔다"</i>로 읽혀서, 실제로 그런 노트(시음일 없는 노트)와 구분되지 않는다.
     *
     * <p>경로 변수를 숫자로 제약하는 것은 {@code /candidates}와의 충돌 회피가 아니라(리터럴이 먼저 매칭된다)
     * <b>{@code /notes/abc}를 상세로 보지 않기</b> 위해서다 — 클라이언트 라우터도 같은 판정을 한다
     * ({@code routes.ts}의 {@code matchNote}).
     */
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<NoteDetailBody> note(@PathVariable long id) {
        return noteService.findDetail(id)
                .map(detail -> ResponseEntity.ok(NoteDetailBody.of(detail)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 노트 메타 수정 — 커피명을 제외한 사실 전부 (ref: TΔ13b·TΔ5b-3, FR-21, AC-5).
     *
     * <p><b>커피명은 요청에 자리가 없고</b>({@link NoteMetaBody}) 서버가 저장된 값을 실어 넘긴다 — 그래서
     * 아래 층의 V-9 대조는 이 경로에서 항상 통과한다. 그 검사를 지우지 않는 이유는 호출부가 하나 더
     * 생기는 날의 방어선이어서다(TΔ4a).
     *
     * <p>그 조회가 <b>404의 근거이기도 하다</b>: 없는 노트에 실을 커피명이 없다는 것과 고칠 노트가 없다는
     * 것이 같은 사실이라, 읽기 한 번이 두 몫을 한다.
     *
     * <p>응답은 <b>갱신된 노트 전문</b>이다 — 서버 정규화(V-14 빈 원두 드롭 등)를 화면이 따라 계산하면
     * 같은 규칙이 클라이언트에 이중화된다.
     */
    @PatchMapping("/{id:\\d+}")
    public ResponseEntity<NoteDetailBody> updateMeta(@PathVariable long id, @RequestBody NoteMetaBody request) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        Optional<Note> stored = noteService.findById(id);
        if (stored.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(
                    NoteDetailBody.of(noteService.updateMeta(id, request.toMeta(stored.get().coffeeName()))));
        } catch (IllegalArgumentException e) {
            // V-9 — 이 경로에서는 닿지 않는다(위 문단). 닿았다면 계약이 깨진 것이므로 조용히 저장하지 않는다.
            log.warn("메타 수정 거부(V-9): {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            // 조회와 쓰기 사이에 노트가 사라졌다 — 같은 사실이므로 같은 상태 코드로 수렴시킨다.
            log.warn("메타 수정 거부(대상 소실): {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 시음일 수정 — 그 날짜의 회차를 갈아끼우고, 본문의 {@code date}가 다르면 <b>사진까지 데리고</b>
     * 날짜를 옮긴다 (ref: TΔ13b·TΔ5b-3, V-10 개정본 delta.md#D-12, AC-5).
     *
     * <p>경로의 {@code date}가 <b>대상</b>이고 본문의 {@code date}가 <b>결과</b>다. 이동처에 이미 기록이
     * 있으면 그날의 회차 뒤로 합쳐지는데(회차 병합), 그 판정도 순서도 전부 아래 층의 규칙이다 — 여기서
     * 하는 일은 파싱·위임·응답 변환뿐이다(백엔드 CLAUDE.md §2).
     *
     * <p><b>회차 0개는 400이다</b>(V-15). 정규화가 빈 회차를 드롭한 결과 하나도 남지 않았다는 것은
     * 저장할 시음이 없다는 뜻이고, 그대로 쓰면 <i>화면에 없는 시음일 삭제 경로</i>가 생긴다 — 시음일 삭제는
     * spec에 없다(TΔ13b 편차 ⑤).
     */
    @PatchMapping("/{id:\\d+}/tasting-days/{date}")
    public ResponseEntity<NoteDetailBody> replaceTastingDay(
            @PathVariable long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody TastingDayBody request) {
        if (request == null || request.date() == null) {
            return ResponseEntity.badRequest().build();
        }
        TastingDay tastingDay = request.toTastingDay();
        if (tastingDay.cups().isEmpty()) {
            log.warn("시음일 수정 거부(회차 없음): noteId={} date={}", id, date);
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(NoteDetailBody.of(noteService.replaceTastingDay(id, date, tastingDay)));
        } catch (IllegalStateException e) {
            // 노트도 대상 시음일도 "고칠 것이 URL에 없다"는 같은 사실이다.
            log.warn("시음일 수정 거부(대상 소실): {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 노트 삭제 — 하위 행도 사진도 남기지 않는 hard delete (ref: TΔ5b-3, 0028 AC-Δ8, AC-6).
     *
     * <p><b>없는 id는 404다</b>(계약). 저장소는 없는 id를 무해하게 지나가지만 — 지울 노트를 <i>읽지
     * 않는다</i>는 규율 때문이다 — 삭제가 실제로 행을 지웠는지는 삭제 자신이 답하므로, 그 규율을 깨지 않고
     * 갈린다. 되돌릴 자리가 없는 조작이라 <i>"지웠다"</i>와 <i>"지울 것이 없었다"</i>를 같은 응답으로
     * 뭉개지 않는다.
     */
    @DeleteMapping("/{id:\\d+}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        return noteService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * 후보 응답 — 배열을 벌거벗겨 내보내지 않고 객체로 감싼다.
     *
     * <p>최상위 JSON 배열은 나중에 필드(페이징 커서·총계)를 더할 자리가 없어 그때 <b>깨는 변경</b>이 된다.
     * 갤러리(TΔ12)가 페이징을 요구할지는 아직 화면이 답하지 않았고, 감싸는 쪽은 그 답이 무엇이든 가산
     * 변경으로 흡수한다.
     */
    public record Candidates(List<NoteCandidate> candidates) {
    }

    /** 실패 로그의 식별 단서 — 커피명은 draft 안에서 불변이라(V-9) 재시도 요청과 짝지을 수 있다. */
    private static String coffeeNameOf(DraftBody request) {
        return request.note().coffeeName() == null ? null : request.note().coffeeName().value();
    }

    /**
     * 커밋 응답 — 저장된 노트의 식별자만 돌려준다.
     *
     * <p>저장된 노트 <b>전체</b>를 싣지 않는 것이 이 task의 판단이다(사용자 확정 2026-08-01, TΔ11 이월 (a)):
     * 기존 노트 병합에서 폼의 메타 수정은 ADR-4에 따라 저장되지 않는데, 그 사실을 알리는 자리는 응답 본문이
     * 아니라 <b>화면 문구</b>다(신규/병합으로 갈린 저장 안내). 사실을 고치는 경로 자체는 상세 수정
     * 화면(TΔ13)이고, 필드 잠금·수정 안내는 그 화면이 설 때 판단한다.
     */
    public record Response(long noteId) {
    }
}
