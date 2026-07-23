package com.devwuu.mocha.llm;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;

/**
 * OpenAI Responses 응답에서 메시지 텍스트를 추출하는 llm 패키지 공유 헬퍼.
 * <p>POLICY: {@code outputText} 추출의 프로덕션 정의는 이 한 곳뿐이다 — llm 어댑터 3종
 * ({@code OpenAiAliasGenerator}·{@code OpenAiUtteranceSegmenter}·{@code OpenAiVisionClient})이
 * 위임한다. 공용 util 패키지가 아니라 같은 패키지 내 배치
 * (ref: specs/coffee-note-agent/plan.md#ADR-67 ④).
 */
final class OpenAiResponseTexts {

    private OpenAiResponseTexts() {
    }

    // Responses 출력 아이템 중 메시지 텍스트만 이어붙인다.
    static String outputText(Response response) {
        StringBuilder sb = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            if (!item.isMessage()) {
                continue;
            }
            for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                if (content.isOutputText()) {
                    sb.append(content.asOutputText().text());
                }
            }
        }
        return sb.toString();
    }
}
