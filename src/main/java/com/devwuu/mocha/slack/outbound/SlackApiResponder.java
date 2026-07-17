package com.devwuu.mocha.slack.outbound;

import com.devwuu.mocha.domain.PendingNote;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.SlackFilesUploadV2Exception;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.chat.ChatUpdateResponse;
import com.slack.api.methods.response.files.FilesUploadV2Response;
import com.slack.api.model.block.LayoutBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link SlackResponder} 구현 — bot token 기반 {@link MethodsClient}로 안내 메시지·카드 이미지를 전송한다
 * (tasks T3-5 / changes/0002 TΔ5).
 * <p>Slack SDK 타입은 이 어댑터에만 존재한다(CLAUDE.md §2). {@link #post 안내 메시지}는 커밋 이후 통지라 전송
 * 실패가 저장을 되돌리면 안 되므로 예외를 잡아 로그로만 남긴다. {@link #postImage 카드 배달}은 실패를 삼키지 않고
 * 던져 호출부가 텍스트 폴백으로 수렴하게 한다(AC-18, plan.md §7).
 */
@Component
public class SlackApiResponder implements SlackResponder {

    private static final Logger log = LoggerFactory.getLogger(SlackApiResponder.class);

    private final MethodsClient methods;
    private final PreviewBlocks previewBlocks;

    public SlackApiResponder(MethodsClient methods, PreviewBlocks previewBlocks) {
        this.methods = methods;
        this.previewBlocks = previewBlocks;
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

    @Override
    public void finalizePreview(String channelId, PendingNote pending, String statusText) {
        // POLICY: 저장/취소 처리 완료 시 미리보기 버튼 제거(1회 소진) — 필드 내용 유지 + 상태 문구 교체.
        //         갱신 실패는 커밋을 되돌리지 않는다 (ref: plan.md#ADR-20, AC-22).
        String previewTs = pending.previewTs();
        if (previewTs == null) {
            // publish 성공 후 재저장 전에 죽은 극단 케이스 — 갱신할 대상 메시지가 없다. 조용히 건너뛴다.
            log.debug("버튼 소진 건너뜀 — preview_ts 없음: channel={}", channelId);
            return;
        }
        try {
            List<LayoutBlock> blocks = previewBlocks.buildFinalized(pending, statusText);
            ChatUpdateResponse res = methods.chatUpdate(r -> r
                    .channel(channelId)
                    .ts(previewTs)
                    .text(PreviewBlocks.FALLBACK_TEXT)
                    .blocks(blocks));
            if (!res.isOk()) {
                log.warn("미리보기 버튼 소진 실패: channel={} ts={} error={}", channelId, previewTs, res.getError());
            }
        } catch (Exception e) {
            // 커밋·배달은 이미 끝났다 — 버튼 소진 실패는 로그로만 남기고 흐름을 끊지 않는다(ADR-20, plan.md §7).
            log.warn("미리보기 버튼 소진 중 오류: channel={} ts={}", channelId, previewTs, e);
        }
    }
}
