package com.devwuu.mocha.slack.outbound;

import java.nio.file.Path;

/**
 * 평문 응답·이미지 배달 송신 경계 — 결과 통지를 채널에 보낸다(에이전트 최종 텍스트·폴백 안내 등,
 * tasks T3-5 / changes/0002 TΔ5).
 * <p>호출부가 Slack SDK 타입을 직접 참조하지 않도록 인터페이스로 분리한다
 * (CLAUDE.md §2 — Slack 타입은 송신 어댑터에만). 구현: {@link SlackApiResponder}.
 * <p>0029 TΔ4에서 확인 미리보기 계열이 통째로 사라지며 {@code finalizePreview}(버튼 1회 소진)가
 * 함께 폐기됐다 — 소진할 버튼도, 그 대상을 가리키던 {@code preview_ts}도 pending과 함께 소멸했다.
 */
public interface SlackResponder {

    /**
     * 채널에 안내 메시지를 보낸다. 커밋 이후 통지이므로 전송 실패가 커밋을 되돌리지 않는다 —
     * 구현체는 예외를 삼키지 말고 로그로 남기되 흐름을 끊지 않는다(plan.md §7).
     */
    void post(String channelId, String text);

    /**
     * 엔트리 카드 JPG를 파일로 채널에 올린다(FR-16, plan §1 [8]). files.uploadV2 경로.
     * <p>실패를 <b>삼키지 않는다</b> — 예외로 던져 호출부가 안내 텍스트 폴백으로 수렴하게 한다
     * (AC-18, plan §7). "흐름을 끊지 않는다"의 책임은 폴백을 처리하는 호출부에 있다.
     * <p>0029 TΔ4 이후 호출부가 없다 — 카드 배달은 저장 커밋 체인에 달려 있었다. 카드는 TΔ9에서
     * 온디맨드 생성·공유로 형태가 바뀌고, 이 Slack 송신 경로는 TΔ16에서 계층째 걷힌다.
     */
    void postImage(String channelId, Path imagePath, String caption);
}
