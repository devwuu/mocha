package com.devwuu.mocha.web;

import com.devwuu.mocha.agent.turn.TurnResult;
import com.devwuu.mocha.agent.turn.TurnRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/agent/turn} — 발화 1건을 에이전트 턴으로 돌리고 응답 텍스트와 제안된 폼 상태를
 * 돌려준다 (ref: changes/0029 tasks.md TΔ6a, delta.md#D-4·#D-10;
 * 계약 정본 {@code src/test/resources/contract/agent-turn.contract.json}).
 *
 * <p><b>이 앱의 첫 REST 컨트롤러다.</b> 종전에 controller 역할을 하던 것은 {@code SlackGateway}였고
 * (백엔드 {@code CLAUDE.md} §2), 이제 같은 턴을 부르는 입구가 둘이다. 컨트롤러가 하는 일은 §2가 정한
 * 대로 <b>파싱·위임·응답 변환</b>뿐이다 — 전처리·조립·루프·트랜스크립트는 {@link TurnRunner}가 소유하고
 * 여기서 다시 짜지 않는다.
 *
 * <p><b>서버 상태를 바꾸지 않는다</b> — 제안은 응답으로만 돌아간다(TΔ4에서 pending이 사라진 결과). 저장은
 * {@code POST /api/notes}(TΔ6b)이고, 그래서 <i>"저장은 확인 이후에만"</i>(ADR-3)이 이 경계와 겹친다.
 *
 * <p>여기서 <b>V-6(draft 대조)이 살아난다</b>: Slack 경로는 폼이 없어 draft를 항상 null로 넘겼고 게이트가
 * 무발동이었다(TΔ4 이월). 요청 본문이 draft를 싣는 순간 하위 출처의 덮어쓰기 거부가 실제로 작동한다.
 *
 * <p>POLICY: 턴 실패는 <b>노트 무변화 + 원문 로그 보존 + 실패 응답</b>으로 수렴한다 — 조용한 유실 금지
 * (ref: plan.md#ADR-48, spec FR-25/AC-63). Slack이 안내 <i>문구</i>를 돌려준 자리에서 REST는 <b>실패
 * 상태</b>를 돌려준다: 계약({@code agent-turn.contract.json})의 응답은 성공 형태뿐이고, 안내를 성공 본문의
 * {@code reply}에 실으면 사용자에게 <i>실패가 정상 응답으로 보인다</i>. 안내 문구의 소유자는 화면이다
 * (TΔ10 {@code ChatScreen}이 이미 실패 자리에 시스템 알림을 남긴다).
 */
@RestController
@RequestMapping("/api/agent")
public class AgentTurnController {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnController.class);

    private final TurnRunner turnRunner;

    public AgentTurnController(TurnRunner turnRunner) {
        this.turnRunner = turnRunner;
    }

    @PostMapping("/turn")
    public ResponseEntity<Response> turn(@RequestBody Request request) {
        String utterance = request.utterance();
        if (utterance == null || utterance.isBlank()) {
            // 빈 턴은 모델 호출만 태우고 아무것도 만들지 않는다. TΔ8a에서 사진이 턴에 실리면
            // "사진만 첨부하고 전송"이 유효한 턴이 되므로(D-11) 이 조건은 그때 완화된다.
            return ResponseEntity.badRequest().build();
        }
        try {
            TurnResult result = turnRunner.run(SingleUser.ID, utterance,
                    request.draft() == null ? null : request.draft().toDraft());
            return ResponseEntity.ok(new Response(result.reply(), DraftBody.of(result.proposal())));
        } catch (Exception e) {
            // POLICY 원문 보존(위 클래스 주석) — 예외 메시지로 폴백 사유를 구분 관측한다(plan §6).
            //         원문은 개인 데이터이므로 logs/ 비커밋 규칙이 그대로 적용된다(NFR-7, 루트 CLAUDE.md §5).
            log.warn("에이전트 턴 폴백(노트 무변화, 원문 보존): user={} 원문={}", SingleUser.ID, utterance, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 턴 요청 — {@code draft}는 첫 턴에 null이다(폼이 없으면 보낼 것이 없다).
     * <p>추가 발화가 폼 전체를 동봉하는 것이 이 델타의 방어선이다(TΔ2): 서버가 작성 중 데이터를 기억하지
     * 않으므로, 이것이 없으면 에이전트가 트랜스크립트만 보고 재제안해 <b>사용자가 고친 값이 되돌아간다</b>
     * (delta.md §1.2).
     */
    public record Request(String utterance, DraftBody draft) {
    }

    /**
     * 턴 응답 — {@code draft}는 제안이 없었던 턴(잡담·조회·검증 거부)에 null이고,
     * <b>그때 클라이언트는 폼을 지우지 않는다</b>.
     */
    public record Response(String reply, DraftBody draft) {
    }
}
