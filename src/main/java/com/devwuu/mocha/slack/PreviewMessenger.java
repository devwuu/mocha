package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.chat.ChatUpdateResponse;
import com.slack.api.model.block.LayoutBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 확인 미리보기 전송/갱신 어댑터 (ref: plan.md §3 sendPreview; tasks T3-3).
 * <p>{@link PreviewBlocks}가 조립한 블록을 Slack으로 내보낸다 — {@code preview_ts}가 없으면 새로 전송(post),
 * 있으면 그 메시지를 편집(update)한다(수정 시 재전송이 아닌 edit, data-model.md#2.3). 전송/편집으로 확정된
 * 미리보기 메시지 ts를 돌려주며, 호출부(제안 tool 구현)가 이를 pending에 반영한다.
 * <p>Slack API 타입은 이 어댑터(수신 계층에 대응하는 송신 어댑터)에만 존재한다.
 */
@Component
public class PreviewMessenger {

    private static final Logger log = LoggerFactory.getLogger(PreviewMessenger.class);

    private final PreviewBlocks previewBlocks;
    private final MethodsClient methods;

    public PreviewMessenger(PreviewBlocks previewBlocks, MethodsClient methods) {
        this.previewBlocks = previewBlocks;
        this.methods = methods;
    }

    /**
     * 미리보기를 전송하거나(신규) 기존 미리보기 메시지를 갱신한다(preview_ts 있으면 edit).
     *
     * @param channelId 전송 대상 채널.
     * @param pending   확인 대기 노트. {@code previewTs}가 null이면 신규 전송, 아니면 해당 메시지 갱신.
     * @return 확정된 미리보기 메시지 ts.
     */
    public String publish(String channelId, PendingNote pending) throws IOException, SlackApiException {
        List<LayoutBlock> blocks = previewBlocks.build(pending);
        String previewTs = pending.previewTs();
        if (previewTs == null) {
            ChatPostMessageResponse res = methods.chatPostMessage(r -> r
                    .channel(channelId)
                    .text(PreviewBlocks.FALLBACK_TEXT)
                    .blocks(blocks));
            if (!res.isOk()) {
                throw new IllegalStateException("미리보기 전송 실패: " + res.getError());
            }
            log.debug("미리보기 전송: channel={} ts={}", channelId, res.getTs());
            return res.getTs();
        }
        ChatUpdateResponse res = methods.chatUpdate(r -> r
                .channel(channelId)
                .ts(previewTs)
                .text(PreviewBlocks.FALLBACK_TEXT)
                .blocks(blocks));
        if (!res.isOk()) {
            throw new IllegalStateException("미리보기 갱신 실패: " + res.getError());
        }
        log.debug("미리보기 갱신: channel={} ts={}", channelId, res.getTs());
        return res.getTs();
    }
}
