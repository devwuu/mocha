package com.devwuu.mocha.slack;

import com.devwuu.mocha.agent.turn.TurnResult;
import com.devwuu.mocha.agent.turn.TurnRunner;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.slack.inbound.IncomingAction;
import com.devwuu.mocha.slack.inbound.IncomingMedia;
import com.devwuu.mocha.slack.inbound.IncomingMessage;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.MochaMessages;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 에이전트 턴 라우터 — 버튼 액션 외 모든 수신을 에이전트 루프로 처리하는 {@link ConversationRouter} 구현
 * (ref: specs/coffee-note-agent/plan.md §1 [1]·[3], §3 route; ADR-44·47·48; changes/0018 TΔ7b).
 * <ul>
 *   <li>텍스트(사진 캡션 포함) → 버퍼 흡수 → OCR 전처리(FR-19, 루프 전 결정론 1콜) → 에이전트 턴
 *       ({@link TurnRunner}) → 최종 텍스트가 Slack 응답(ADR-44). 의도 게이트는 없다 — 에이전트 자체가
 *       대화 경계다(ADR-47, FR-24).</li>
 *   <li>버튼 → 도달하지 않는다. 0029 TΔ4에서 확인 미리보기가 pending과 함께 사라졌다({@link #onAction}).</li>
 *   <li>사진 → 버퍼·스테이징 배관(FR-10, {@link SlackPhotoIntake}) 직접 위임 — 캡션 텍스트는
 *       {@link SlackGateway}가 별도 {@link IncomingMessage}로 이어 보내 에이전트 턴이 된다.</li>
 * </ul>
 * <p><b>0029 TΔ6a에서 턴 자체가 여기서 빠져나갔다</b> — 전처리·컨텍스트 조립·루프·수거함·트랜스크립트
 * 축적은 {@link TurnRunner}가 소유하고, 이 클래스에는 <i>Slack이라서 있는 것</i>만 남는다: 수신 형식
 * ({@link IncomingMessage}), 응답 배달({@link SlackResponder}), 사진 버퍼 그룹핑(FR-10). 같은 턴을
 * {@code POST /api/agent/turn}이 부른다.
 * <p><b>0029 TΔ4 이후 이 경로에는 저장 수단이 없다.</b> 제안 결과는 {@link TurnResult}로 나오지만
 * 그것을 폼으로 받아 [저장]을 누를 클라이언트가 Slack에는 없다 — 캡처를 앱이 가져가는 중간 상태이고,
 * Slack 계층 자체는 TΔ16에서 걷힌다(tasks.md 순서 규율, 사용자 확정 2026-07-31).
 * <p>POLICY: 에이전트 턴 실패(모델 오류·tool 상한·검증 반복)는 노트 무변화 + 재요청 안내 +
 * 원문 로그 보존 — 조용한 유실 금지 (ref: specs/coffee-note-agent/plan.md#ADR-48, spec AC-63).
 */
@Component
public class AgentConversationRouter implements ConversationRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationRouter.class);

    private final TurnRunner turnRunner;
    private final SlackPhotoIntake photoIntake;
    private final SlackResponder responder;
    // 버퍼 윈도우 판정의 시간원(Asia/Seoul — V-3)은 config 공통 빈 주입(ADR-63).
    private final Clock clock;

    // 협력자 조립은 config가 소유(ADR-63, RouterConfig) — 라우터는 주입만 받는다(생성자 1종,
    // 프로덕션·테스트 공용). 공개 승격 이유는 Clock 빈과 동일(config가 타 패키지 — ADR-63).
    public AgentConversationRouter(
            TurnRunner turnRunner,
            SlackPhotoIntake photoIntake,
            SlackResponder responder,
            Clock clock) {
        this.turnRunner = turnRunner;
        this.photoIntake = photoIntake;
        this.responder = responder;
        this.clock = clock;
    }

    @Override
    public void onMessage(IncomingMessage message) {
        String userId = message.userId();
        String channelId = message.channelId();
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);

            // FR-10 버퍼 그룹핑: 텍스트보다 먼저 도착해 버퍼링된 사진이 윈도우 안이면 이 턴에 묶는다.
            // 소비(clearBuffer)는 제안이 성공한 뒤에만 — 실패 시 사진이 버퍼에 남아 재시도로 살아남는다.
            List<String> bufferNames = photoIntake.absorbFreshBuffer(userId, now).orElse(List.of());

            // OCR은 tool이 아니라 루프 전 결정론 전처리다(ADR-44, 사용자 확정) — 사진 있으면 항상 1콜,
            // 실패·무정보는 빈 결과로 컨텍스트에서 빠진다(FR-19, AC-28 — 조립기가 거른다).
            VisionExtraction ocr = photoIntake.readPhotoInfo(userId, bufferNames, new VisionHint(null, null));

            // 0029 TΔ2의 draft(= 폼 상태)는 이 경로에서 항상 없다 — TΔ4에서 pending을 걷어내며 과도기
            // 어댑터(pending → TurnDraft)가 함께 사라졌고, Slack에는 폼이 없어 채울 것이 남지 않았다.
            // 그 자리는 요청 본문의 draft가 채운다(TΔ6a POST /api/agent/turn). 결과적으로 이 경로에서는
            // V-6(출처 우선순위 대조)이 무발동이다 — 대조할 폼 상태 자체가 없기 때문이다.
            TurnResult result = turnRunner.run(userId, message.text(), null, ocr);

            // 모델의 최종 텍스트가 곧 Slack 응답이다(ADR-44).
            responder.post(channelId, result.reply());
            if (result.proposal() != null && !bufferNames.isEmpty()) {
                // 사진이 이번 제안에 실렸다 — 버퍼만 비운다(스테이징 원본은 저장 확정 시 commit이 옮긴다, FR-10).
                photoIntake.clearBuffer(userId);
            }
        } catch (Exception e) {
            // POLICY: 에이전트 턴 실패는 노트 무변화 + 재요청 안내 + 원문 로그 보존 — 조용한 유실 금지
            //         (ref: specs/coffee-note-agent/plan.md#ADR-48, spec FR-25/AC-63). 제안 tool이 상태를
            //         쓰지 않게 된 뒤로 되돌릴 부분 성공 자체가 없다. 폴백 사유는 예외 메시지로 구분 관측(plan §6).
            log.warn("에이전트 턴 폴백(노트 무변화, 원문 보존): user={} 원문={}", userId, message.text(), e);
            responder.post(channelId, MochaMessages.AGENT_TURN_FAILED);
        }
    }

    @Override
    public void onAction(IncomingAction action) {
        // POLICY: 저장/취소 확정은 사용자 조작만 — 자연어·에이전트 미경유 (ref: plan.md#ADR-3 불변, #ADR-45).
        // 0029 TΔ4에서 이 경로의 분기가 통째로 사라졌다: 버튼을 실은 확인 미리보기가 pending과 함께
        // 폐기됐으므로 도달할 action_id가 없다. 확정 조작은 앱의 폼이 가져가고(TΔ6b POST /api/notes),
        // 커밋 접힘(FoldTrigger.SAVE_COMMIT·CANCEL_COMMIT)의 배선 지점도 그때 거기서 다시 선다 —
        // 그 사이 트랜스크립트는 TTL로만 접힌다(TΔ3의 접힘 2규칙 중 하나가 일시적으로 무배선).
        log.warn("액션 수신 — 도달하지 않아야 할 경로(미리보기 폐기됨): actionId={} user={}",
                action.actionId(), action.userId());
    }

    @Override
    public void onMedia(IncomingMedia media) {
        // 사진 버퍼·스테이징·포맷 검증 배관은 불변(ADR-29, delta UNCHANGED) — flow 미경유로 직접 위임한다(TΔ8a).
        // 캡션 텍스트는 게이트웨이가 별도 IncomingMessage로 이어 보내 에이전트 턴(onMessage)이 된다.
        photoIntake.receive(media);
    }
}
