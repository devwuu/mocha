package com.devwuu.mocha.web;

import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.service.NoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/notes} — 폼 확정 저장(= 구 Slack [저장] 버튼)
 * (ref: changes/0029 tasks.md TΔ6b, plan.md#ADR-3·#ADR-4;
 * 계약 정본 {@code src/test/resources/contract/note-commit.contract.json}).
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
 * <p><b>사진 확정은 아직 여기 없다</b> — 구 {@code SlackCommitHandler}가 커밋 뒤에 하던 일인데 TΔ16에서
 * 사진 입구가 사라져 확정할 스테이징이 생기지 않는다(버퍼 정리는 버퍼와 함께 소멸했다). TΔ8b가
 * {@code NoteService.commit} 안에 붙인다(시그니처 무변화 — 이 컨트롤러는 그때도 안 바뀐다).
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
        try {
            // POLICY: 사용자 [저장] 확정을 거친 요청만 여기 온다 — 이 호출이 그 확정이다
            //         (ref: plan.md#ADR-3, spec AC-4).
            Note saved = noteService.commit(request.note().toNote(), request.match());
            // 커밋 접힘 — 확정된 작업의 문맥은 버린다(ADR-46 커밋 규칙, TΔ3 개정 2규칙 중 하나).
            transcript.clear(SingleUser.ID, FoldingChatMemory.FoldTrigger.SAVE_COMMIT);
            return ResponseEntity.ok(new Response(saved.id()));
        } catch (IllegalArgumentException e) {
            // 저장할 시음 엔트리가 없다 — 클라이언트가 보낸 것이 폼이 아니다(NoteService 입력 검증).
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
