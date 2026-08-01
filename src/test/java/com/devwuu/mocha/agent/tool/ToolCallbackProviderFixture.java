package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.service.NoteService;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * TΔ4b(changes/0025): 테스트용 {@link ToolCallbackProvider} 조립 픽스처 — 위치 인자를 늘어놓던
 * 직접 생성자 호출을 한 곳으로 모은다(R-7, AC-Δ4). 협력자가 늘거나 순서가 바뀌어도 테스트마다 null을
 * 세어 맞출 필요가 없고, 각 테스트는 <b>자기가 실제로 쓰는 협력자만</b> 이름으로 지정한다.
 * <p>기본값은 전부 null이다 — 계약 스냅샷처럼 tool 정의만 캡처하는 테스트가 {@code toolkit().build()}
 * 한 줄로 끝나고, 지정하지 않은 협력자를 실행 경로가 건드리면 NPE로 즉시 드러난다(fake를 조용히 끼워
 * 넣어 미접촉 전제를 가리지 않는다).
 * <p>예외는 기록 검증기다 — 제안 tool을 쓰는 테스트가 전부 같은 줄을 반복하던 자리라 프로덕션
 * 배선(TurnConfig)과 같은 조합을 기본값으로 만든다: {@link #clock(Clock)} 지정 시 파생된다. 다른
 * 검증기가 필요하면 {@link #recordValidator(RecordProposalValidator)}로 덮는다. 수정 검증기는
 * {@code propose_edit}과 함께 폐기됐고(0029 TΔ1), pending 저장소·미리보기 송신은 제안이 서버 상태를
 * 쓰지 않게 되며 협력자 목록에서 빠졌다(0029 TΔ4).
 * <p>{@link ToolCallbackProviderTest}는 실 협력자 조립 자체가 검증 대상이라 생성자 직접 호출을 유지한다 —
 * 위치 인자 배선이 어긋나면 픽스처 경유 테스트는 함께 눈이 멀기 때문에, 원 생성자를 그대로 통과시키는
 * 지점을 하나 남긴다(R-7 명시 예외).
 */
public final class ToolCallbackProviderFixture {

    private NoteService noteService;
    private ObjectMapper mapper;
    private RecordProposalValidator recordValidator;
    private Clock clock;

    private ToolCallbackProviderFixture() {
    }

    /** 협력자 전부 null인 픽스처 — 여기서 필요한 것만 지정한다. */
    public static ToolCallbackProviderFixture toolkit() {
        return new ToolCallbackProviderFixture();
    }

    public ToolCallbackProviderFixture noteService(NoteService noteService) {
        this.noteService = noteService;
        return this;
    }

    public ToolCallbackProviderFixture mapper(ObjectMapper mapper) {
        this.mapper = mapper;
        return this;
    }

    public ToolCallbackProviderFixture recordValidator(RecordProposalValidator recordValidator) {
        this.recordValidator = recordValidator;
        return this;
    }

    /** 시계 지정 — 미지정 기록 검증기는 이 시계로 파생된다(프로덕션 배선과 동일 조합). */
    public ToolCallbackProviderFixture clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    public ToolCallbackProvider build() {
        // 기록 검증기는 시계 파생 — 시계 미지정이면 null로 남겨 "지정 안 한 협력자" 신호를 유지한다.
        RecordProposalValidator record = recordValidator != null ? recordValidator
                : clock != null ? new RecordProposalValidator(clock) : null;
        return new ToolCallbackProvider(noteService, mapper, record, clock);
    }
}
