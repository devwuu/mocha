package com.devwuu.mocha.eval;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * eval 케이스 1건 — {@code eval/cases/<id>/case.yaml}의 인메모리 표현 (ref: plan.md#ADR-68, changes/0026 TΔ1).
 * <p>케이스는 <b>실발화 리플레이 + 결정론 계약 판정</b>의 단위다: 고정 {@code today}에 발화 시퀀스를
 * {@code AgentConversationRouter.onMessage}로 주입하고, 턴이 끝난 뒤의 <b>사후 상태</b>(제안 결과·기록된 tool
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
 * @param utterances 발화 시퀀스. 2개 이상이면 멀티턴 — 같은 라우터 인스턴스에 순차 주입한다(트랜스크립트 승계).
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
     * <p>0029 TΔ4에서 {@code pending} 픽스처가 사라졌다 — 서버가 작성 중 상태를 갖지 않으므로 심을 곳이
     * 없다. <b>"폼이 떠 있는 상태에서 추가 발화"를 표현하는 자리는 {@code draft}가 다시 세운다</b>(TΔ21).
     *
     * @param notes 노트 픽스처 디렉터리(도메인 {@code Note} JSON 모음). null이면 노트 0건에서 시작.
     *              <b>파일명 오름차순으로 INSERT되고 id는 DB가 발급한다</b> — 픽스처의 {@code id} 필드는
     *              무시되므로, 케이스가 특정 id를 전제하면 파일명 순서로 고정한다(changes/0028 TΔ6b).
     * @param draft <b>첫 턴에 실릴 폼 상태</b>({@code TurnDraft} JSON 파일 — {@code {note, match}}).
     *              null이면 첫 턴은 폼이 없는 상태(종전 전부의 거동)다.
     *              <p>구 {@code pending} 픽스처의 자리를 받지만 <b>성격이 반대다</b>: 그것은 서버에 심는
     *              행이었고 이것은 <b>클라이언트가 요청에 실어 보내는 값</b>이다(ADR-80). 심을 저장소가
     *              없으므로 러너가 턴 인자로 넘긴다.
     *              <p><b>둘째 턴부터는 케이스가 아니라 러너가 정한다</b> — 직전 턴의 제안 결과가 다음 턴의
     *              draft가 된다(제안이 없으면 직전 값 유지). 실사용 클라이언트가 응답의 draft를 폼에 넣고
     *              다음 발화에 되싣는 것과 같은 거동이고, 케이스가 턴마다 draft를 적으면 <b>실사용에 없는
     *              조합</b>을 재게 된다.
     *              <p>그래서 이 픽스처가 표현하는 것은 정확히 <b>"사용자가 손으로 고쳐 둔 값"</b>이다 —
     *              모델이 만든 것도, 직전 턴이 남긴 것도 아닌 값을 첫 턴에 세우는 유일한 수단이라
     *              V-6 draft 대조의 케이스가 여기서만 성립한다.
     */
    public record Initial(String notes, String draft) {
    }

    /**
     * 기대 계약 — 지정한 항목만 판정한다(미지정 = 무관심). 최소 1종은 있어야 한다.
     *
     * @param unchangedNotes 노트 저장소 무변화 기대. 커밋은 사용자 확정에서만 일어나므로(ADR-3) 텍스트 턴은
     *                       사실상 항상 true지만, 그 불변을 케이스가 명시적으로 지키게 둔다(회귀 가드).
     */
    public record Expect(Proposal proposal, Tools tools, Count rejections, Boolean unchangedNotes) {
    }

    /**
     * 제안 결과 기대 — 상태 + draft 부분 일치.
     * <p>0029 TΔ4에서 관측 창이 pending 행에서 제안 수거함으로 옮겨졌다. 함께 사라진 것: {@code mode}
     * (수정 세션이 TΔ1에서 폐기돼 {@code record} 하나만 남았다)와 {@code unchanged}(초기 pending 바이트와의
     * 비교였는데 비교 대상이 없다).
     *
     * @param state 턴 종료 후 제안 유무.
     * @param draft 제안 내용(Note 구조, snake_case) 경로 단언. 전체 일치가 아니라 <b>부분 일치</b>다 —
     *              LLM이 채우는 필드 대부분은 비결정이고, 케이스가 실제로 보려는 필드만 못박는다.
     * @param match 제안의 <b>매칭 판정</b>(`{type, note_id, date}`) 경로 단언 — changes/0029 TΔ21 신설.
     *              <p>{@code draft}와 뿌리를 나눈 이유: 전자는 {@code Note} 구조라 {@code entries[0].date} 같은
     *              경로가 노트 안을 가리키는데, {@code match}는 노트 밖의 형제 필드다. 한 뿌리로 합치면
     *              기존 케이스의 경로가 전부 한 단계 깊어진다.
     *              <p><b>tool 인자 단언으로 갈음하지 않는 이유</b>: {@code calls[].args}는 <i>모델이 무엇을
     *              보냈는가</i>를 보지만 이 필드는 <i>서버 검증을 통과해 폼에 도달한 것이 무엇인가</i>를 본다.
     *              {@code edit} 제안이 거부된 뒤 {@code existing}으로 다시 나가 성사된 흐름은 전자로는
     *              통과하고 후자로는 걸린다 — D-14가 없애려는 실패(*"의도는 수정, 결과는 추가"*)가 정확히
     *              그 모양이라 관측 창이 뒤쪽이어야 한다.
     */
    public record Proposal(State state, List<PathAssertion> draft, List<PathAssertion> match) {

        /**
         * {@code created} — 턴 종료 시 제안 결과가 있다.<br>
         * {@code absent} — 제안이 없다(제안 tool 미호출 또는 전부 거부).
         */
        public enum State {
            CREATED, ABSENT
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
     *             거부된 호출의 인자는 제안 결과에 남지 않아 draft 단언으로 볼 수 없는 자리를 메운다.
     */
    public record Call(String name, Integer min, Integer max, List<PathAssertion> args) {
    }

    /** 횟수 범위 — 둘 중 최소 하나는 지정돼야 한다(둘 다 null이면 아무것도 판정하지 않는 빈 기대). */
    public record Count(Integer min, Integer max) {
    }

    /**
     * JSON 경로 단언 — 정확히 한 종류만 지정한다(둘을 겹쳐 쓰면 무엇이 깨졌는지가 흐려진다).
     *
     * @param path    {@code entries[0].cups[0].recipe.grind} 문법. 필드명은 저장 포맷 그대로 snake_case.
     * @param value   값 동등 비교(스칼라).
     * @param size    배열 크기.
     * @param present 존재 여부 — {@code true}면 null 아닌 값이 있어야 하고, {@code false}면 없어야 한다.
     */
    /**
     * 경로 하나에 대한 단언 — {@code value}·{@code size}·{@code present}·{@code blank} 중 <b>정확히 1개</b>.
     *
     * @param blank <b>"비었다"</b> — 부재·null·길이 0을 <b>한 값으로 본다</b>(changes/0029 TΔ21 신설).
     *              <p>{@code present: false}와 다르다: 그쪽은 부재·null만 참이고 빈 배열은 거짓이다.
     *              <p><b>왜 필요한가</b>: 보강 트리거가 정확히 이 술어다
     *              ({@code Sourced.valuesOrEmpty(officialNotes).isEmpty()} — null과 {@code []}를 같게 본다).
     *              케이스가 트리거보다 좁은 술어를 걸면 <b>시스템이 신경 쓰지 않는 차이로 빨개진다</b> —
     *              모델이 {@code null} 대신 {@code []}를 내는 것은 보강 관점에서 같은 상태인데 케이스만
     *              뒤집힌다. 회귀 가드가 재야 하는 것은 <i>시스템이 판정에 쓰는 조건</i>이다.
     */
    public record PathAssertion(String path, Object value, Integer size, Boolean present, Boolean blank) {

        /** 파싱된 경로 — 문법은 로더가 이미 검증했으므로 여기서는 실패하지 않는다. */
        public EvalPath parsedPath() {
            return EvalPath.parse(path);
        }
    }
}
