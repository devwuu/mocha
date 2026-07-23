package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.agent.tool.EditProposal;
import com.devwuu.mocha.agent.tool.ProposeEditArgs;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code propose_edit} 인자의 서버 검증 진입점 — strict schema가 보장 못 하는 <b>값 수준 규칙</b>을
 * 결정론으로 강제한다 (ref: specs/coffee-note-agent/data-model.md#5, plan#ADR-45, changes/0018 TΔ1).
 * <p>검증 진입점은 툴별 클래스 1개씩이다(ADR-64 — 새 툴 추가 시 파일 1개씩 성장, record는
 * {@link RecordProposalValidator}). 양 진입점이 공유하는 규칙 패밀리는 같은 패키지의 구체 클래스에 위임한다
 * — 출처 {@code SourceRules}(V-5·V-14), 회차 {@code BrewRules}(V-1·V-8·V-15), 단일 대기
 * {@code SinglePendingGate}. 진입점 고유 검증(필수 날짜·환각 필터(대상 엔트리 실존)·교체 결과 회차 0개
 * 거부·같은 날짜 이동의 null 정규화)은 여기 남는다.
 * <ul>
 *   <li>patch의 beans·brews는 배열 통째 교체 의미 — null만 유지(data-model §3.4)</li>
 *   <li>이동 충돌(V-10) 계산은 여기가 아니라 제안 수용 지점(ProposalTools)의 몫 — 여기서는 같은 날짜
 *       이동의 null 정규화만</li>
 *   <li>다중 날짜 게이트(V-16)는 record 전용 — edit 경로에 날짜 수 게이트 금지(ADR-60), 그래서 시계
 *       의존이 없다</li>
 * </ul>
 * <p>POLICY: 제안 tool의 서버 검증 실패는 오류 사유를 tool 결과로 반환 — 조용한 드롭·서버 대행 금지
 * (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-9). 순수 도메인 계층 — SDK·I/O 무관, 대상 노트
 * 리졸브(slug → Note)와 pending 조회는 호출부(tool 구현, TΔ6)의 몫이다.
 * <p>POLICY: agent/tool/은 tool 정의·인자·검증만 — 턴 전처리·컨텍스트 운반체는 agent/turn/에,
 * 새 인터페이스 없이 구체 클래스로 (ref: plan.md#ADR-64).
 */
public class EditProposalValidator {

    /**
     * {@code propose_edit} 검증 — 통과 시 도메인 타입으로 정규화된 {@link EditProposal}.
     * patch의 beans·brews는 배열 통째 교체 의미다 — null만 유지(data-model §3.4).
     *
     * @param args    strict schema를 통과한 미검증 인자.
     * @param note    slug로 리졸브된 대상 노트 — 미존재 오류는 호출부(tool 구현)가 이미 반환했다.
     * @param pending 현재 확인 대기 — 없으면 null. 단일 대기 판정 입력.
     */
    public ToolValidation<EditProposal> validate(ProposeEditArgs args, Note note, PendingNote pending) {
        try {
            LocalDate targetDate = ValidationSupport.parseDate("date", args.date());
            if (targetDate == null) {
                throw new RejectedException("date가 없다 — 수정 대상 엔트리의 날짜(YYYY-MM-DD)를 채워라.");
            }
            // 환각 필터(get_note 미존재 오류와 같은 정신): 실존하지 않는 엔트리를 대상으로 수정 세션이 열리지 않게.
            if (note.entries().stream().noneMatch(e -> targetDate.equals(e.date()))) {
                throw new RejectedException("노트 '" + note.slug() + "'에는 " + targetDate
                        + " 시음 엔트리가 없다 — get_note로 실제 엔트리 날짜를 확인해라.");
            }
            SinglePendingGate.requireSameEditTargetOrFree(pending, note.slug(), targetDate);

            ProposeEditArgs.Patch patch = args.patch() == null ? ProposeEditArgs.Patch.empty() : args.patch();
            Sourced<String> roastery = SourceRules.sourced(
                    "roastery", patch.roastery(), SourceRules.ENRICHABLE_SOURCES);
            // patch의 beans·brews는 통째 교체 — 인자 부재(null)만 유지다(data-model §3.4).
            List<Bean> beans = patch.beans() == null ? null : SourceRules.beans(patch.beans());
            Sourced<String> roastLevel = SourceRules.sourced(
                    "roast_level", patch.roastLevel(), SourceRules.ENRICHABLE_SOURCES);
            Sourced<List<String>> officialNotes = SourceRules.sourcedNotes(patch.officialNotes());
            List<Brew> brews = patch.brews() == null ? null : BrewRules.brews(patch.brews());
            // V-15: 교체 결과 회차 0개인 엔트리는 만들 수 없다 — 기록이 통째로 비게 되는 patch는 거부.
            if (brews != null && brews.isEmpty()) {
                throw new RejectedException("brews 교체 결과 회차가 0개가 된다 — 엔트리에는 감상(tasting)이나 "
                        + "레시피(recipe)를 담은 회차가 최소 1개 있어야 한다(V-15). 회차를 남기거나 brews를 빼고 보내라.");
            }

            // 대상 자신의 날짜로는 이동이 아니다 — null로 정규화(구 수정 flow의 충돌 판정 승계).
            // 이동 충돌(V-10)은 여기서 계산하지 않는다 — 제안 수용 지점(ProposalTools)이 현 draft 기준으로 재계산한다.
            LocalDate newDate = ValidationSupport.parseDate("new_date", patch.newDate());
            if (targetDate.equals(newDate)) {
                newDate = null;
            }

            return ToolValidation.ok(new EditProposal(
                    note.slug(), targetDate, roastery, beans, roastLevel, officialNotes,
                    brews, newDate));
        } catch (RejectedException rejection) {
            return ToolValidation.rejected(rejection.getMessage());
        }
    }
}
