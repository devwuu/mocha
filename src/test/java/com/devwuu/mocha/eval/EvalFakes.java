package com.devwuu.mocha.eval;

import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import com.devwuu.mocha.slack.outbound.PreviewBlocks;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import com.devwuu.mocha.slack.outbound.SlackResponder;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * eval 러너가 실 협력자 대신 끼우는 대체물 모음 (ref: plan.md#ADR-68, changes/0026 TΔ2).
 *
 * <p>대체 범위는 <b>Slack·렌더·사진</b>뿐이다 — 이 셋은 판정 대상이 아니면서 외부 부수효과(네트워크·
 * Chromium·파일)를 일으킨다. 판정 대상인 것들(LLM 루프·세그먼터·검증기·저장소)은 전부 실물로 남는다:
 * 대체하는 순간 케이스가 프로덕션과 다른 경로를 돌아 회귀 가드로서의 값을 잃는다(findings-TΔ0 §1.2).
 *
 * <p>v1은 텍스트 턴만 다루므로(ADR-68) 사진 협력자 3종은 <b>비어 있는 채로 접촉되지 않는 것이 정상</b>이다 —
 * 라우터가 버퍼 부재를 확인하고 즉시 빠져나가는 경로만 지난다. 그래서 stub이 아니라 빈 구현이고, 텍스트
 * 케이스가 사진 경로를 건드리면 조용히 통과하지 않고 아래 {@code UnsupportedOperationException}으로 드러난다.
 */
final class EvalFakes {

    private EvalFakes() {
    }

    /**
     * Slack 미접촉 responder — 전송 텍스트를 순서대로 캡처한다.
     * <p>폴백 판정에 쓴다: {@code onMessage}는 예외를 삼키고 {@code AGENT_TURN_FAILED}를 보내므로(ADR-48),
     * 러너는 던져진 예외가 아니라 이 캡처로 턴 실패를 안다(findings-TΔ0 §1.1).
     */
    static final class CapturingResponder implements SlackResponder {

        final List<String> posted = new ArrayList<>();

        @Override
        public void post(String channelId, String text) {
            posted.add(text);
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            posted.add("[image] " + imagePath);
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            // 버튼 소진은 커밋 경로의 몫 — v1 텍스트 턴은 지나지 않는다.
        }
    }

    /** 미리보기 전송을 고정 ts로 대체한다 — Slack 미접촉(스모크 관례 승계). */
    static final class StubPreviewMessenger extends PreviewMessenger {

        StubPreviewMessenger() {
            super(new PreviewBlocks(), null); // MethodsClient 미사용 — publish를 통째로 대체한다.
        }

        @Override
        public String publish(String channelId, PendingNote pending) {
            return "1720000000.000123";
        }
    }

    /**
     * 렌더 미수행 — 카드 굽기(Thymeleaf + Playwright/Chromium)를 통째로 뺀다.
     * <p>v1은 커밋하지 않으므로 카드 렌더 경로는 {@code send_entry_card}로만 닿는데, 그 tool이 판정 대상이
     * 아니어서 실 렌더러를 물릴 이유가 없다(findings-TΔ0 §1.2 — {@code FakeCardImageRenderer} 불필요 판정).
     */
    static final class NoOpNoteRenderer implements NoteRenderer {

        @Override
        public void renderAll() {
            // no-op
        }

        @Override
        public List<Path> renderEntryCard(long noteId, LocalDate date) {
            return List.of();
        }

        @Override
        public void removeEntryCard(long noteId, LocalDate date) {
            // no-op
        }
    }

    /** 스테이징 사진 없음 — v1 텍스트 턴은 읽기만 스치고 지나간다. */
    static final class EmptyPhotoStore implements PhotoStore {

        @Override
        public String stage(String userId, String filename, byte[] bytes) {
            throw new UnsupportedOperationException("eval v1은 텍스트 턴만 다룬다(ADR-68 비범위)");
        }

        @Override
        public List<StagedImage> readStaged(String userId) {
            return List.of();
        }

        @Override
        public List<String> commit(String userId, String noteFolder, String date) {
            throw new UnsupportedOperationException("eval은 커밋하지 않는다 — 쓰기는 제안까지만(AC-59)");
        }

        @Override
        public void discard(String userId) {
            // no-op
        }

        @Override
        public void moveEntryPhotos(String noteFolder, String fromDate, String toDate) {
            throw new UnsupportedOperationException("eval은 커밋하지 않는다");
        }

        @Override
        public List<String> stagedUserIds() {
            return List.of();
        }
    }

    /** 버퍼 사진 없음 — 라우터의 버퍼 흡수가 항상 빈 결과로 빠져나간다(vision 콜 0). */
    static final class EmptyPhotoBufferStore implements PhotoBufferStore {

        @Override
        public void put(String userId, PhotoBuffer buffer) {
            throw new UnsupportedOperationException("eval v1은 텍스트 턴만 다룬다(ADR-68 비범위)");
        }

        @Override
        public Optional<PhotoBuffer> get(String userId) {
            return Optional.empty();
        }

        @Override
        public void clear(String userId) {
            // no-op — 제안 성공 턴이 무조건 부르는 자리가 아니라 버퍼가 있을 때만 닿는다.
        }
    }

    /** OCR 미수행 — 이미지 목록이 비면 라우터가 여기까지 오지도 않지만, 도달해도 vision을 부르지 않는다. */
    static final class NoOpPhotoInfoExtractor extends PhotoInfoExtractor {

        NoOpPhotoInfoExtractor() {
            super(null, 4); // VisionClient 미사용 — extract를 통째로 대체한다(스모크 관례 승계).
        }

        @Override
        public VisionExtraction extract(List<StagedImage> images, VisionHint hint) {
            return VisionExtraction.empty();
        }
    }
}
