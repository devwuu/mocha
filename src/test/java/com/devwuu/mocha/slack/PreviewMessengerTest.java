package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.slack.api.methods.MethodsClient;
import com.slack.api.RequestConfigurator;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.chat.ChatUpdateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3-3: 미리보기 전송/갱신 분기 검증 — preview_ts 유무로 post/update가 갈리는지(수정 시 edit),
 * 확정 ts 반환, 실패 응답 시 예외 전파(CLAUDE.md §5.2 외부 어댑터 모킹).
 */
class PreviewMessengerTest {

    private final MethodsClient methods = mock(MethodsClient.class);
    private final PreviewMessenger messenger = new PreviewMessenger(new PreviewBlocks(), methods);

    private static PendingNote pending(String previewTs) {
        Note draft = new Note(
                "coffeevera-yirgacheffe-g1", "커피베라 예가체프 G1",
                Sourced.user("커피베라"), null, null, null, null, List.of(),
                List.of(new Entry(LocalDate.of(2026, 7, 10), "새콤함", Rating.GOOD, List.of(), OffsetDateTime.now())),
                OffsetDateTime.now(), OffsetDateTime.now());
        return new PendingNote(draft, MatchInfo.newNote(), previewTs, OffsetDateTime.now());
    }

    @Test
    @DisplayName("preview_ts 없으면 chatPostMessage로 신규 전송하고 새 ts를 반환한다")
    void postsWhenNoPreviewTs() throws Exception {
        ChatPostMessageResponse ok = new ChatPostMessageResponse();
        ok.setOk(true);
        ok.setTs("1720000000.000100");
        when(methods.chatPostMessage(any(RequestConfigurator.class))).thenReturn(ok);

        String ts = messenger.publish("C1", pending(null));

        assertEquals("1720000000.000100", ts);
        verify(methods).chatPostMessage(any(RequestConfigurator.class));
        verify(methods, never()).chatUpdate(any(RequestConfigurator.class));
    }

    @Test
    @DisplayName("preview_ts 있으면 chatUpdate로 기존 메시지를 편집한다 (수정 시 재전송 아닌 edit)")
    void updatesWhenPreviewTsPresent() throws Exception {
        ChatUpdateResponse ok = new ChatUpdateResponse();
        ok.setOk(true);
        ok.setTs("1720000000.000999");
        when(methods.chatUpdate(any(RequestConfigurator.class))).thenReturn(ok);

        String ts = messenger.publish("C1", pending("1720000000.000999"));

        assertEquals("1720000000.000999", ts);
        verify(methods).chatUpdate(any(RequestConfigurator.class));
        verify(methods, never()).chatPostMessage(any(RequestConfigurator.class));
    }

    @Test
    @DisplayName("Slack 응답이 ok=false면 예외를 삼키지 않고 전파한다")
    void throwsOnSlackError() throws Exception {
        ChatPostMessageResponse fail = new ChatPostMessageResponse();
        fail.setOk(false);
        fail.setError("channel_not_found");
        when(methods.chatPostMessage(any(RequestConfigurator.class))).thenReturn(fail);

        assertThrows(IllegalStateException.class, () -> messenger.publish("C1", pending(null)));
    }
}
