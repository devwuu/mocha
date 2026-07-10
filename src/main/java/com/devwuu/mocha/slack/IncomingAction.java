package com.devwuu.mocha.slack;

/**
 * Slack Block Kit 액션(버튼 등)에서 필요한 필드만 뽑아낸 내부 표현.
 * <p>ADR-3: 저장/취소는 자연어가 아니라 이 버튼 액션으로만 받는다. {@code actionId}로 저장/취소를 가른다
 * (구체 분기는 T3-2 라우터·T3-5 커밋 파이프라인 몫).
 *
 * @param userId    누른 사용자 id.
 * @param channelId 액션이 발생한 채널 id.
 * @param actionId  버튼 action_id — 저장/취소 등 의도 식별자.
 * @param value     버튼 value — 필요 시 대상 식별에 사용.
 * @param messageTs 버튼이 달린 메시지의 ts = 미리보기 메시지 timestamp(preview_ts, data-model §2.3).
 */
public record IncomingAction(String userId, String channelId, String actionId, String value, String messageTs) {
}
