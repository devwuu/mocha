package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.tool.validation.EditProposalValidator;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;

/**
 * TΔ4b(changes/0025): 테스트용 {@link ToolCallbackProvider} 조립 픽스처 — 위치 인자 11개를 늘어놓던
 * 직접 생성자 호출을 한 곳으로 모은다(R-7, AC-Δ4). 협력자가 늘거나 순서가 바뀌어도 테스트마다 null을
 * 세어 맞출 필요가 없고, 각 테스트는 <b>자기가 실제로 쓰는 협력자만</b> 이름으로 지정한다.
 * <p>기본값은 전부 null이다 — 계약 스냅샷처럼 tool 정의만 캡처하는 테스트가 {@code toolkit().build()}
 * 한 줄로 끝나고, 지정하지 않은 협력자를 실행 경로가 건드리면 NPE로 즉시 드러난다(fake를 조용히 끼워
 * 넣어 미접촉 전제를 가리지 않는다).
 * <p>예외는 검증기 2종이다 — 제안 tool을 쓰는 테스트가 전부 같은 두 줄을 반복하던 자리라 프로덕션
 * 배선(RouterConfig)과 같은 조합을 기본값으로 만든다: 기록 검증기는 {@link #clock(Clock)} 지정 시 파생되고,
 * 무상태·무인자인 수정 검증기(ADR-60)는 시계와 무관하게 항상 생성된다. 다른 검증기가 필요하면
 * {@link #recordValidator(RecordProposalValidator)}·{@link #editValidator(EditProposalValidator)}로 덮는다.
 * <p>{@link ToolCallbackProviderTest}는 실 협력자 조립 자체가 검증 대상이라 생성자 직접 호출을 유지한다 —
 * 위치 인자 배선이 어긋나면 픽스처 경유 테스트는 함께 눈이 멀기 때문에, 원 생성자를 그대로 통과시키는
 * 지점을 하나 남긴다(R-7 명시 예외).
 */
public final class ToolCallbackProviderFixture {

    private NoteRepository noteRepository;
    private NoteRenderer noteRenderer;
    private SlackResponder responder;
    private Path artifactDir;
    private ObjectMapper mapper;
    private PendingStore pendingStore;
    private PreviewMessenger previewMessenger;
    private RecordProposalValidator recordValidator;
    private EditProposalValidator editValidator;
    private FoldingChatMemory transcript;
    private Clock clock;

    private ToolCallbackProviderFixture() {
    }

    /** 협력자 전부 null인 픽스처 — 여기서 필요한 것만 지정한다. */
    public static ToolCallbackProviderFixture toolkit() {
        return new ToolCallbackProviderFixture();
    }

    public ToolCallbackProviderFixture noteRepository(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
        return this;
    }

    public ToolCallbackProviderFixture noteRenderer(NoteRenderer noteRenderer) {
        this.noteRenderer = noteRenderer;
        return this;
    }

    public ToolCallbackProviderFixture responder(SlackResponder responder) {
        this.responder = responder;
        return this;
    }

    public ToolCallbackProviderFixture artifactDir(Path artifactDir) {
        this.artifactDir = artifactDir;
        return this;
    }

    public ToolCallbackProviderFixture mapper(ObjectMapper mapper) {
        this.mapper = mapper;
        return this;
    }

    public ToolCallbackProviderFixture pendingStore(PendingStore pendingStore) {
        this.pendingStore = pendingStore;
        return this;
    }

    public ToolCallbackProviderFixture previewMessenger(PreviewMessenger previewMessenger) {
        this.previewMessenger = previewMessenger;
        return this;
    }

    public ToolCallbackProviderFixture recordValidator(RecordProposalValidator recordValidator) {
        this.recordValidator = recordValidator;
        return this;
    }

    public ToolCallbackProviderFixture editValidator(EditProposalValidator editValidator) {
        this.editValidator = editValidator;
        return this;
    }

    public ToolCallbackProviderFixture transcript(FoldingChatMemory transcript) {
        this.transcript = transcript;
        return this;
    }

    /** 시계 지정 — 미지정 검증기 2종은 이 시계로 파생된다(프로덕션 배선과 동일 조합). */
    public ToolCallbackProviderFixture clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    public ToolCallbackProvider build() {
        // 기록 검증기만 시계 파생 — 시계 미지정이면 null로 남겨 "지정 안 한 협력자" 신호를 유지한다.
        RecordProposalValidator record = recordValidator != null ? recordValidator
                : clock != null ? new RecordProposalValidator(clock) : null;
        // 수정 검증기는 무상태·무인자(ADR-60, RouterConfig도 no-arg 빈)라 시계와 무관하게 항상 만든다 —
        // 시계에 묶어 두면 propose_edit만 쓰는 테스트가 null을 받아 ProposalTools 안쪽 NPE로 새어 나온다(CR25-9).
        EditProposalValidator edit = editValidator != null ? editValidator : new EditProposalValidator();
        return new ToolCallbackProvider(noteRepository, noteRenderer, responder, artifactDir, mapper,
                pendingStore, previewMessenger, record, edit, transcript, clock);
    }
}
