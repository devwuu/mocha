package com.devwuu.mocha.slack;

import java.nio.file.Path;

/**
 * 평문 응답·이미지 배달 송신 경계 — 미리보기(Block Kit)가 아닌 결과 통지를 채널에 보낸다
 * (저장 완료 카드 배달·만료 안내·취소 안내·폴백 등, tasks T3-5 / changes/0002 TΔ5).
 * <p>{@link PreviewMessenger}가 확인 미리보기 전용 송신 어댑터라면, 이쪽은 커밋 결과 통지용이다.
 * 오케스트레이션(ConfirmationFlow)이 Slack SDK 타입을 직접 참조하지 않도록 인터페이스로 분리한다
 * (CLAUDE.md §2 — Slack 타입은 송신 어댑터에만). 구현: {@link MethodsSlackResponder}.
 */
public interface SlackResponder {

    /**
     * 채널에 안내 메시지를 보낸다. 커밋 이후 통지이므로 전송 실패가 커밋을 되돌리지 않는다 —
     * 구현체는 예외를 삼키지 말고 로그로 남기되 흐름을 끊지 않는다(plan.md §7).
     */
    void post(String channelId, String text);

    /**
     * 엔트리 카드 JPG를 파일로 채널에 올린다(FR-16, plan §1 [8]). files.uploadV2 경로.
     * <p>커밋 이후 배달이라 저장을 되돌리지 않지만, 실패를 <b>삼키지 않는다</b> — 예외로 던져
     * 호출부({@link DefaultConfirmationFlow#confirmSave})가 안내 텍스트 폴백으로 수렴하게 한다
     * (AC-18, plan §7). "흐름을 끊지 않는다"의 책임은 폴백을 처리하는 호출부에 있다.
     */
    void postImage(String channelId, Path imagePath, String caption);
}
