package com.devwuu.mocha.slack;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.SlackFilesUploadV2Exception;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.files.FilesUploadV2Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@link SlackResponder} 구현 — bot token 기반 {@link MethodsClient}로 안내 메시지·카드 이미지를 전송한다
 * (tasks T3-5 / changes/0002 TΔ5).
 * <p>Slack SDK 타입은 이 어댑터에만 존재한다(CLAUDE.md §2). {@link #post 안내 메시지}는 커밋 이후 통지라 전송
 * 실패가 저장을 되돌리면 안 되므로 예외를 잡아 로그로만 남긴다. {@link #postImage 카드 배달}은 실패를 삼키지 않고
 * 던져 호출부가 텍스트 폴백으로 수렴하게 한다(AC-18, plan.md §7).
 */
@Component
public class MethodsSlackResponder implements SlackResponder {

    private static final Logger log = LoggerFactory.getLogger(MethodsSlackResponder.class);

    private final MethodsClient methods;

    public MethodsSlackResponder(MethodsClient methods) {
        this.methods = methods;
    }

    @Override
    public void post(String channelId, String text) {
        try {
            ChatPostMessageResponse res = methods.chatPostMessage(r -> r.channel(channelId).text(text));
            if (!res.isOk()) {
                log.warn("안내 메시지 전송 실패: channel={} error={}", channelId, res.getError());
            }
        } catch (Exception e) {
            // 커밋은 이미 끝났다 — 통지 실패는 로그로만 남기고 흐름을 끊지 않는다(plan.md §7).
            log.warn("안내 메시지 전송 중 오류: channel={}", channelId, e);
        }
    }

    @Override
    public void postImage(String channelId, Path imagePath, String caption) {
        try {
            FilesUploadV2Response res = methods.filesUploadV2(r -> r
                    .channel(channelId)
                    .file(imagePath.toFile())
                    .filename(imagePath.getFileName().toString())
                    .initialComment(caption));
            if (!res.isOk()) {
                // 실패를 삼키지 않는다 — 호출부(confirmSave)가 텍스트 폴백으로 수렴하도록 던진다(AC-18).
                throw new IllegalStateException("카드 이미지 업로드 실패: channel=" + channelId + " error=" + res.getError());
            }
        } catch (IOException | SlackApiException | SlackFilesUploadV2Exception e) {
            throw new IllegalStateException("카드 이미지 업로드 중 오류: channel=" + channelId, e);
        }
    }
}
