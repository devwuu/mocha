package com.devwuu.mocha.eval;

import com.devwuu.mocha.domain.PendingNote;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * eval 케이스 1건 — {@code eval/cases/<id>/case.yaml}의 인메모리 표현 (ref: plan.md#ADR-68, changes/0026 TΔ1).
 * <p>케이스는 <b>실발화 리플레이 + 결정론 계약 판정</b>의 단위다: 고정 {@code today}에 발화 시퀀스를
 * {@code AgentConversationRouter.onMessage}로 주입하고, 턴이 끝난 뒤의 <b>사후 상태</b>(pending diff·기록된 tool
 * 시퀀스·검증 거부·노트 무변화)로만 통과를 판정한다. 응답 문구는 단언하지 않는다 — 비결정 출력의 계약만 본다
 * (백엔드 CLAUDE.md §5.3, ADR-68 POLICY).
 *
 * <p>필드 구성 근거는 changes/0026 {@code findings-TΔ0.md} §2.3이다 — 관측 실패 유형 7종을 표현하는 데 실제로
 * 필요했던 최소 표면만 담는다(루트 CLAUDE.md §4 right-sizing).
 *
 * @param id         케이스 식별자. <b>폴더명이 소유한다</b> — {@code case.yaml}에 쓰지 않는다(진실 이중화 방지).
 * @param dir        케이스 폴더 경로. {@code initial}의 상대 참조를 해석하는 기준점.
 * @param origin     관측 출처(델타 번호·로그 날짜 등). 케이스가 왜 존재하는지의 유일한 기록이라 필수다(ADR-68).
 * @param today      고정 시각 — {@code Clock.fixed}의 instant. 날짜만이 아니라 <b>시각까지</b> 고정하는 이유는
 *                   턴 타임스탬프·TTL 판정이 시각에서 파생돼 재현이 흔들리기 때문이다(findings-TΔ0 §1.1).
 *                   노트 id의 재현성은 시계가 아니라 회차별 스키마 재생성이 진다(changes/0028 TΔ6b).
 * @param utterances 발화 시퀀스. 2개 이상이면 멀티턴 — 같은 라우터 인스턴스에 순차 주입한다(트랜스크립트·pending 승계).
 * @param initial    턴 진입 전 저장소 상태(동봉 픽스처 참조).
 * @param expect     기대 계약.
 */
public record EvalCase(
        String id,
        Path dir,
        String origin,
        OffsetDateTime today,
        List<String> utterances,
        Initial initial,
        Expect expect
) {

    /** 폴더에서 온 신원(id·경로)을 채운 사본 — 로더 전용. YAML은 이 두 필드를 담지 않는다. */
    public EvalCase withIdentity(String id, Path dir) {
        return new EvalCase(id, dir, origin, today, utterances, initial, expect);
    }

    /**
     * 초기 저장소 상태 — 케이스 폴더에 동봉된 픽스처를 가리키는 <b>폴더 상대 경로</b>.
     * <p>인라인 필드가 아니라 파일 참조인 이유: 러너가 도메인 JSON 그대로 실 저장소에 심으면 되고
     * (직렬화 왕복이 판정 대상 — findings-TΔ0 §1.2), 실사용에서 노트를 그대로 떠 오는 박제도 그만큼 싸진다(ADR-69 ①).
     *
     * @param notes   노트 픽스처 디렉터리(도메인 {@code Note} JSON 모음). null이면 노트 0건에서 시작.
     *                <b>파일명 오름차순으로 INSERT되고 id는 DB가 발급한다</b> — 픽스처의 {@code id} 필드는
     *                무시되므로, 케이스가 특정 id를 전제하면 파일명 순서로 고정한다(changes/0028 TΔ6b).
     * @param pending 초기 pending 파일({@code pending.json} 포맷). null이면 대기 없음에서 시작.
     */
    public record Initial(String notes, String pending) {
    }

    /**
     * 기대 계약 — 지정한 항목만 판정한다(미지정 = 무관심). 최소 1종은 있어야 한다.
     *
     * @param unchangedNotes 노트 저장소 무변화 기대. 커밋은 [저장] 버튼에서만 일어나므로(ADR-3) v1 텍스트 턴은
     *                       사실상 항상 true지만, 그 불변을 케이스가 명시적으로 지키게 둔다(회귀 가드).
     */
    public record Expect(Pending pending, Tools tools, Count rejections, Boolean unchangedNotes) {
    }

    /**
     * pending 기대 — 상태 + draft 부분 일치.
     *
     * @param state 턴 종료 후 pending 상태.
     * @param mode  {@code record} | {@code edit}. null이면 무관심.
     * @param draft draft(Note 구조, snake_case) 경로 단언. 전체 일치가 아니라 <b>부분 일치</b>다 —
     *              LLM이 채우는 필드 대부분은 비결정이고, 케이스가 실제로 보려는 필드만 못박는다.
     */
    public record Pending(State state, PendingNote.Mode mode, List<PathAssertion> draft) {

        /**
         * {@code created} — 턴 종료 시 pending이 존재한다(초기 pending이 있었다면 그것과 달라야 한다).<br>
         * {@code absent} — pending이 없다(제안 자체가 일어나지 않음).<br>
         * {@code unchanged} — 초기 pending이 바이트 단위로 그대로다(게이트 거부 — 제안이 막혔음의 증거).
         */
        public enum State {
            CREATED, ABSENT, UNCHANGED
        }
    }

    /**
     * tool 호출 기대 — 러너의 {@code ToolCallback} 기록 데코레이터가 캡처한 시퀀스를 판정한다.
     *
     * @param sequenceContains 부분 시퀀스(순서 유지, 연속일 필요 없음). 사이에 다른 호출이 끼어도 통과한다 —
     *                         모델이 정보를 더 조회하는 것 자체는 실패가 아니기 때문.
     * @param total            전체 호출 수 하한/상한. 잡담 경계 케이스의 "tool 0회"가 여기 걸린다(AC-20·59).
     * @param calls            이름별 호출 수·인자 단언.
     */
    public record Tools(List<String> sequenceContains, Count total, List<Call> calls) {
    }

    /**
     * 이름별 tool 호출 기대.
     *
     * @param name tool 이름({@code propose_record} 등).
     * @param min  호출 수 하한. "첫 회 거부 후 자가 정정"은 같은 tool 2회 이상으로 드러난다(FR-25).
     * @param max  호출 수 상한.
     * @param args 인자 JSON 경로 단언 — <b>그 이름의 호출 중 최소 하나</b>가 전부 만족하면 통과.
     *             거부된 호출의 인자는 pending에 남지 않아 pending 단언으로 볼 수 없는 자리를 메운다.
     */
    public record Call(String name, Integer min, Integer max, List<PathAssertion> args) {
    }

    /** 횟수 범위 — 둘 중 최소 하나는 지정돼야 한다(둘 다 null이면 아무것도 판정하지 않는 빈 기대). */
    public record Count(Integer min, Integer max) {
    }

    /**
     * JSON 경로 단언 — 정확히 한 종류만 지정한다(둘을 겹쳐 쓰면 무엇이 깨졌는지가 흐려진다).
     *
     * @param path    {@code entries[0].brews[0].recipe.grind} 문법. 필드명은 저장 포맷 그대로 snake_case.
     * @param value   값 동등 비교(스칼라).
     * @param size    배열 크기.
     * @param present 존재 여부 — {@code true}면 null 아닌 값이 있어야 하고, {@code false}면 없어야 한다.
     */
    public record PathAssertion(String path, Object value, Integer size, Boolean present) {

        /** 파싱된 경로 — 문법은 로더가 이미 검증했으므로 여기서는 실패하지 않는다. */
        public EvalPath parsedPath() {
            return EvalPath.parse(path);
        }
    }
}
