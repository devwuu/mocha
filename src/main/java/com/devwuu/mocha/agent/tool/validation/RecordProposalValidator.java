package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.agent.tool.ProposeRecordArgs;
import com.devwuu.mocha.agent.tool.RecordProposal;
import com.devwuu.mocha.agent.turn.TastingDateDetector;
import com.devwuu.mocha.agent.turn.TurnUtterance;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Sourced;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code propose_record} 인자의 서버 검증 진입점 — strict schema가 보장 못 하는 <b>값 수준 규칙</b>을
 * 결정론으로 강제한다 (ref: specs/coffee-note-agent/data-model.md#5, plan#ADR-45, changes/0018 TΔ1).
 * <p>검증 진입점은 툴별 클래스 1개씩이다(ADR-64 — 새 툴 추가 시 파일 1개씩 성장, edit는
 * {@link EditProposalValidator}). 양 진입점이 공유하는 규칙 패밀리는 같은 패키지의 구체 클래스에 위임한다
 * — 출처 {@code SourceRules}(V-5·V-14), 회차 {@code BrewRules}(V-1·V-8·V-15), 단일 대기
 * {@code SinglePendingGate}. 진입점 고유 검증(필수값·회차 0개 거부·V-16 다중 날짜 게이트·match 판정)은
 * 여기 남는다.
 * <ul>
 *   <li>V-1 rating 4범주, V-5 source enum 제약, V-8 recipe 정규화, V-11 my_taste 병존</li>
 *   <li>V-14 beans 정규화, V-15 회차(brews) 정규화 — 빈 회차 드롭·회차 0개 거부(changes/0021)</li>
 *   <li>단일 대기 거부(FR-22/AC-30)</li>
 *   <li>V-16 다중 날짜 게이트(record 전용) — 원문 다중 날짜의 분해 우회 제안 거부(changes/0023)</li>
 * </ul>
 * <p>POLICY: 제안 tool의 서버 검증 실패는 오류 사유를 tool 결과로 반환 — 조용한 드롭·서버 대행 금지
 * (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-9). 순수 도메인 계층 — SDK·I/O 무관, pending 조회는
 * 호출부(tool 구현, TΔ6)의 몫이다. 시계는 V-16의 연도 없는 표기("7/18") 해석 기준일에만 쓰인다.
 * <p>POLICY: agent/tool/은 tool 정의·인자·검증만 — 턴 전처리·컨텍스트 운반체는 agent/turn/에,
 * 새 인터페이스 없이 구체 클래스로 (ref: plan.md#ADR-64).
 */
public class RecordProposalValidator {

    private final Clock clock;

    // 시계는 config 공통 빈 주입(ADR-63) — 테스트는 고정 시계로 V-16의 연도 없는 표기 해석을 결정론으로 만든다.
    public RecordProposalValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@code propose_record} 검증 — 통과 시 도메인 타입으로 정규화된 {@link RecordProposal}.
     *
     * @param args      strict schema를 통과한 미검증 인자.
     * @param pending   현재 확인 대기 — 없으면 null. 단일 대기 판정 입력.
     * @param utterance 이번 턴의 사용자 원문·세그먼트 컨텍스트 — 다중 날짜 게이트(V-16)의 판정 입력.
     *                  이번 턴 원문만 본다 — 트랜스크립트의 과거 발화는 탐지 대상이 아니다(ADR-60).
     */
    public ToolValidation<RecordProposal> validate(ProposeRecordArgs args, PendingNote pending,
                                                   TurnUtterance utterance) {
        try {
            Sourced<String> coffeeName = SourceRules.sourced(
                    "coffee_name", args.coffeeName(), SourceRules.COFFEE_NAME_SOURCES);
            if (coffeeName == null) {
                throw new RejectedException("coffee_name이 비어 있다 — 커피 이름은 기록의 정체성이라 없으면 "
                        + "기록을 만들 수 없다. 사용자에게 커피 이름을 물어봐라.");
            }
            Sourced<String> roastery = SourceRules.sourced(
                    "roastery", args.roastery(), SourceRules.ENRICHABLE_SOURCES);
            SinglePendingGate.requireUpdatableOrFree(
                    pending, coffeeName.value(), roastery == null ? null : roastery.value());
            List<Bean> beans = SourceRules.beans(args.beans());
            Sourced<String> roastLevel = SourceRules.sourced(
                    "roast_level", args.roastLevel(), SourceRules.ENRICHABLE_SOURCES);
            Sourced<List<String>> officialNotes = SourceRules.sourcedNotes(args.officialNotes());

            LocalDate targetDate = ValidationSupport.parseDate("target_date", args.targetDate());
            if (targetDate == null) {
                throw new RejectedException("target_date가 없다 — 시음일(YYYY-MM-DD)을 채워라. "
                        + "상대 날짜(\"어제\")는 컨텍스트의 today 기준으로 해석해 절대 날짜로 보내라.");
            }
            requireSingleDateOrSegmented(utterance, targetDate);
            MatchInfo match = toMatchInfo(args.match());

            List<Brew> brews = BrewRules.brews(args.brews());
            // V-15: 드롭 후 회차 0개인 엔트리는 저장을 거부한다 — 기록할 내용이 없음(사유는 tool 결과로).
            if (brews.isEmpty()) {
                throw new RejectedException("기록할 회차 내용이 없다 — brews에 감상(tasting)이나 레시피(recipe) 중 "
                        + "최소 하나를 담은 회차를 채워야 저장할 수 있다(V-15). 사용자에게 그날의 감상이나 레시피를 물어봐라.");
            }
            List<String> sources = ValidationSupport.dropBlanks(args.sources());
            NoteMeta meta = new NoteMeta(coffeeName, roastery, beans, roastLevel, officialNotes, sources);
            return ToolValidation.ok(new RecordProposal(meta, targetDate, brews, match));
        } catch (RejectedException rejection) {
            return ToolValidation.rejected(rejection.getMessage());
        }
    }

    // POLICY: 다중 날짜 게이트는 record 전용 — propose_edit 경로에 날짜 수 게이트 금지(날짜 이동·정정
    //         발화는 날짜 2개가 정당하고, 스키마+단일 대기가 이미 강제) (ref: plan.md#ADR-60, data-model.md#V-16).
    // V-16: 분해(ADR-61)를 우회한 뭉뚱그림 제안의 최종 방어선. 원문에서 서로 다른 절대 날짜 2개 이상이
    // 탐지되면, 세그먼트 분해가 수행됐고 target_date가 탐지 집합 안에 있을 때만 통과한다. 세그먼트는
    // 라우터의 턴 전처리(TΔ3b)가 주입한다 — 세그먼터 실패 턴은 컨텍스트 부재 = 전부 거부로, 분리 안내
    // 폴백(ADR-61)과 정합한다. 통과 기준은 집합 소속뿐 — 가장 이른 날짜 강제는 프롬프트의 몫이라
    // "저장 후 이어서" 턴의 나중 날짜 제안을 게이트가 막지 않는다.
    // 거부 사유는 판단 근거(탐지 집합·위반 이유·다음 행동)를 담아 루프 내 자가 정정을 돕는다 —
    // bare rejection 금지(ADR-60 POLICY).
    private void requireSingleDateOrSegmented(TurnUtterance utterance, LocalDate targetDate) {
        NavigableSet<LocalDate> detected = TastingDateDetector.detect(
                utterance == null ? null : utterance.rawText(), LocalDate.now(clock));
        if (detected.size() < 2) {
            return;
        }
        if (utterance.segments() == null) {
            throw new RejectedException("원문에서 서로 다른 시음 날짜 " + detected.size() + "개("
                    + joinDates(detected) + ")가 탐지됐다 — 세그먼트 분해 없이 여러 날짜를 한 기록으로 "
                    + "뭉뚱그릴 수 없다(V-16). 제안하지 말고 사용자에게 한 날짜씩 나눠 보내달라고 안내해라.");
        }
        if (!detected.contains(targetDate)) {
            throw new RejectedException("target_date " + targetDate + "는 원문에서 탐지된 시음 날짜 집합("
                    + joinDates(detected) + ") 밖이다(V-16). 가장 이른 날짜(" + detected.first()
                    + ")의 세그먼트 내용부터 제안해라.");
        }
    }

    private static String joinDates(NavigableSet<LocalDate> dates) {
        return dates.stream().map(LocalDate::toString).collect(Collectors.joining(", "));
    }

    private static MatchInfo toMatchInfo(ProposeRecordArgs.MatchArg match) {
        if (match == null || match.type() == null) {
            throw new RejectedException("match가 없다 — 신규면 {\"type\":\"new\"}, 기존 노트 대상이면 "
                    + "{\"type\":\"existing\",\"slug\":...,\"date\":...}를 채워라.");
        }
        return switch (match.type()) {
            case "new" -> MatchInfo.newNote();
            case "existing" -> {
                if (ValidationSupport.blankToNull(match.slug()) == null) {
                    throw new RejectedException("match.type=existing인데 slug가 없다 — 대상 노트 slug를 채워라.");
                }
                LocalDate date = ValidationSupport.parseDate("match.date", match.date());
                if (date == null) {
                    throw new RejectedException("match.type=existing인데 date가 없다 — 대상 날짜(YYYY-MM-DD)를 채워라.");
                }
                yield MatchInfo.existing(match.slug(), date);
            }
            default -> throw new RejectedException("match.type '" + match.type()
                    + "'는 허용되지 않는다 — new|existing 중 하나여야 한다.");
        };
    }
}
