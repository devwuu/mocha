package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.agent.tool.ProposeRecordArgs;
import com.devwuu.mocha.agent.tool.RecordProposal;
import com.devwuu.mocha.agent.turn.TastingDateDetector;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Source;
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
 * <p>changes/0029 TΔ1 이후 <b>유일한 검증 진입점</b>이다 — {@code propose_edit}과 단일 대기 게이트가 함께
 * 폐기됐다(delta 0029 D-1·D-2). 진입점이 하나로 줄며 백로그 R-3(try/catch 중복)·R-4(RejectedException
 * 결합 미강제)의 근거도 소멸했다. 규칙 패밀리는 같은 패키지의 구체 클래스에 위임한다 — 출처
 * {@code SourceRules}(V-5·V-14), 회차 {@code BrewRules}(V-1·V-8·V-15). 진입점 고유 검증(필수값·회차 0개
 * 거부·V-16 다중 날짜 게이트·match 판정)은 여기 남는다.
 * <ul>
 *   <li>V-1 rating 4범주, V-5 source enum 제약, V-8 recipe 정규화, V-11 my_taste 병존</li>
 *   <li>V-14 beans 정규화, V-15 회차(brews) 정규화 — 빈 회차 드롭·회차 0개 거부(changes/0021)</li>
 *   <li>V-16 다중 날짜 게이트(record 전용) — 원문 다중 날짜의 분해 우회 제안 거부(changes/0023)</li>
 *   <li>V-6 draft 대조(changes/0029 TΔ2) — 턴 입력 draft의 상위 출처 값을 하위 출처가 덮는 제안 거부</li>
 * </ul>
 * <p>POLICY: 제안 tool의 서버 검증 실패는 오류 사유를 tool 결과로 반환 — 조용한 드롭·서버 대행 금지
 * (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-9). 순수 도메인 계층 — SDK·I/O 무관하고, 확인 대기
 * (pending)를 판정 입력으로 보지 않는다. 시계는 V-16의 연도 없는 표기("7/18") 해석 기준일에만 쓰인다.
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
     * @param utterance 이번 턴의 사용자 원문·세그먼트 컨텍스트 — 다중 날짜 게이트(V-16)의 판정 입력.
     *                  이번 턴 원문만 본다 — 트랜스크립트의 과거 발화는 탐지 대상이 아니다(ADR-60).
     * @param draft     작성 중인 폼 상태 — 출처 우선순위 대조(V-6)의 판정 입력. 폼이 없는 턴은 null.
     */
    public ToolValidation<RecordProposal> validate(ProposeRecordArgs args, TurnUserMessage utterance,
                                                   TurnDraft draft) {
        try {
            Sourced<String> coffeeName = SourceRules.sourced(
                    "coffee_name", args.coffeeName(), SourceRules.COFFEE_NAME_SOURCES);
            if (coffeeName == null) {
                throw new RejectedException("coffee_name이 비어 있다 — 커피 이름은 기록의 정체성이라 없으면 "
                        + "기록을 만들 수 없다. 사용자에게 커피 이름을 물어봐라.");
            }
            Sourced<String> roastery = SourceRules.sourced(
                    "roastery", args.roastery(), SourceRules.ENRICHABLE_SOURCES);
            List<Bean> beans = SourceRules.beans(args.beans());
            Sourced<String> roastLevel = SourceRules.sourced(
                    "roast_level", args.roastLevel(), SourceRules.ENRICHABLE_SOURCES);
            Sourced<List<String>> officialNotes = SourceRules.sourcedNotes(args.officialNotes());
            requireDraftPriorityKept(draft, coffeeName, roastery, roastLevel, officialNotes);

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
                throw new RejectedException("기록할 회차 내용이 없다 — brews에 감상(review)이나 레시피(recipe) 중 "
                        + "최소 하나를 담은 회차를 채워야 저장할 수 있다(V-15). 사용자에게 그날의 감상이나 레시피를 물어봐라.");
            }
            List<String> sources = ValidationSupport.dropBlanks(args.sources());
            NoteMeta meta = new NoteMeta(coffeeName, roastery, beans, roastLevel, officialNotes, sources);
            return ToolValidation.ok(new RecordProposal(meta, targetDate, brews, match));
        } catch (RejectedException rejection) {
            return ToolValidation.rejected(rejection.getMessage());
        }
    }

    // POLICY: 다중 날짜 게이트는 기록(propose_record) 경로 전용 (ref: plan.md#ADR-60, data-model.md#V-16).
    //         종전 "propose_edit 경로에 금지"라는 대비항은 그 tool과 함께 소멸했다(0029 TΔ1).
    // V-16: 분해(ADR-61)를 우회한 뭉뚱그림 제안의 최종 방어선. 원문에서 서로 다른 절대 날짜 2개 이상이
    // 탐지되면, 세그먼트 분해가 수행됐고 target_date가 탐지 집합 안에 있을 때만 통과한다. 세그먼트는
    // 라우터의 턴 전처리(TΔ3b)가 주입한다 — 세그먼터 실패 턴은 컨텍스트 부재 = 전부 거부로, 분리 안내
    // 폴백(ADR-61)과 정합한다. 통과 기준은 집합 소속뿐 — 가장 이른 날짜 강제는 프롬프트의 몫이라
    // "저장 후 이어서" 턴의 나중 날짜 제안을 게이트가 막지 않는다.
    // 거부 사유는 판단 근거(탐지 집합·위반 이유·다음 행동)를 담아 루프 내 자가 정정을 돕는다 —
    // bare rejection 금지(ADR-60 POLICY).
    private void requireSingleDateOrSegmented(TurnUserMessage utterance, LocalDate targetDate) {
        // 원문이 없으면 탐지할 날짜도 없다 — 게이트 비발동을 여기서 명시한다(타 클래스의 detect(null)=빈 집합
        // 암묵 불변식에 기대지 않기 위함. 판정 결과는 동일).
        if (utterance == null) {
            return;
        }
        NavigableSet<LocalDate> detected = TastingDateDetector.detect(utterance.rawText(), LocalDate.now(clock));
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

    // POLICY: draft 대조는 V-6(출처 우선순위 user > photo > search)의 결정론 강제 지점이다 — 턴 입력
    //         draft에 이미 있는 값을 더 낮은 출처가 다른 값으로 덮는 제안은 거부한다
    //         (ref: data-model.md#V-6, changes/0029 open-questions.md#OQ-4R·tasks TΔ2).
    //         사용자가 폼에서 고친 값이 재제안의 검색·사진 보강으로 되돌아가는 것이 이 델타가 없애려는
    //         실패(delta 0029 §1.2)의 재발 경로라, 프롬프트 지시 위에 방어선을 하나 더 둔다.
    //
    // ⚠️ 동일성 키를 coffee_name만으로 잡는 이유 — 구 SinglePendingGate가 터진 자리다(delta 0029 §1.2).
    //    그 게이트는 "같은 커피인가"를 coffee_name+roastery로 판정했는데 roastery가 곧 수정 대상이라,
    //    로스터리를 고치려면 로스터리가 이미 같아야 하는 모순이 생겼다. 여기서는 V-9(커피명 불변 — 이름
    //    변경 요청은 오타 정정 포함 예외 없이 거부)가 coffee_name을 draft 안에서 바뀌지 않는 값으로
    //    못박으므로, "수정 대상 필드가 동일성 키에 섞이는" 구조 자체가 성립하지 않는다. 커피명이 다르면
    //    다른 커피의 제안이고 draft를 통째로 대체하므로(0029 TΔ1 확정) 대조하지 않는다.
    //
    // beans는 대조 대상이 아니다 — 배열이라 안정적인 요소 키가 없고, 블렌드 분해·병합으로 요소 수가
    // 바뀌는 것이 정상 경로다(V-14). 요소 짝짓기를 추측해 거부하면 위와 같은 종류의 오거부를 새로 만든다.
    // beans의 V-6는 종전대로 프롬프트·미리보기 확인이 맡는다.
    private static void requireDraftPriorityKept(TurnDraft draft, Sourced<String> coffeeName,
                                                 Sourced<String> roastery, Sourced<String> roastLevel,
                                                 Sourced<List<String>> officialNotes) {
        if (draft == null) {
            return;
        }
        Note note = draft.note();
        String draftName = Sourced.valueOrNull(note.coffeeName());
        if (draftName == null || !draftName.equals(coffeeName.value())) {
            return;
        }
        requireNoDowngrade("roastery", note.roastery(), roastery);
        requireNoDowngrade("roast_level", note.roastLevel(), roastLevel);
        requireNoDowngrade("official_notes", note.officialNotes(), officialNotes);
    }

    // 필드 1개의 대조. 거부는 "draft에 값이 있고 + 제안이 다른 값으로 바꿨고 + 그 출처가 더 낮을 때"뿐이다.
    // 통과시키는 셋: ① 같은 값 재보고(출처만 낮아도 사용자가 잃는 값이 없다) ② 값 제거(제안 null — 발화로
    // 지우는 정당한 경로이지 덮어쓰기가 아니다) ③ 같거나 높은 출처의 정정(사용자 재발화가 여기 든다).
    // 거부 사유는 판단 근거(양쪽 값·출처)와 다음 행동을 담는다 — bare rejection 금지(ADR-60 POLICY).
    private static <T> void requireNoDowngrade(String field, Sourced<T> draftField, Sourced<T> proposed) {
        // source가 null인 draft 값은 우선순위를 판정할 근거가 없다 — 판정 불가는 통과다(fail-open).
        if (draftField == null || draftField.value() == null || draftField.source() == null) {
            return;
        }
        if (proposed == null || proposed.value() == null || Objects.equals(draftField.value(), proposed.value())) {
            return;
        }
        if (rank(proposed.source()) <= rank(draftField.source())) {
            return;
        }
        throw new RejectedException("draft의 " + field + "는 이미 " + draftField.source().json() + " 출처 값("
                + draftField.value() + ")인데 제안이 " + proposed.source().json() + " 출처 값("
                + proposed.value() + ")으로 덮었다 — 출처 우선순위 user > photo > search를 어긴다(V-6). "
                + "사용자가 이미 보고 고칠 수 있었던 값이니 draft 값을 그대로 두고 다시 제안해라. "
                + "정말 바꿔야 한다면 사용자에게 물어 확인한 뒤 source=user로 보내라.");
    }

    // V-6 우선순위의 서열 — 작을수록 상위다(user > photo > search).
    private static int rank(Source source) {
        return switch (source) {
            case USER -> 0;
            case PHOTO -> 1;
            case SEARCH -> 2;
        };
    }

    // POLICY: existing과 edit은 같은 (note_id, date)를 요구하지만 뜻이 반대다 — 전자는 그 노트에 회차를
    //         더하는 것이고 후자는 그 날짜의 엔트리를 갈아끼우는 것이다. 축을 합치면 "수정하려던 것이
    //         회차 추가로 저장되는" 조합이 생긴다 (ref: changes/0029 delta.md#D-14 ②).
    // 대상 실존 검사(노트·엔트리)와 V-9 최종 방어는 여기 없다 — 저장소를 읽어야 하고 이 클래스는
    // 순수 도메인 계층이다(위 클래스 주석). 그 몫은 ProposalTools의 환각 필터가 진다(TΔ29a).
    private static MatchInfo toMatchInfo(ProposeRecordArgs.MatchArg match) {
        if (match == null || match.type() == null) {
            throw new RejectedException("match가 없다 — 신규면 {\"type\":\"new\"}, 기존 노트에 더하는 시음이면 "
                    + "{\"type\":\"existing\",\"note_id\":...,\"date\":...}, 기존 기록을 고치는 것이면 "
                    + "{\"type\":\"edit\",\"note_id\":...,\"date\":...}를 채워라.");
        }
        return switch (match.type()) {
            case "new" -> MatchInfo.newNote();
            case "existing" -> MatchInfo.existing(
                    requireNoteId(match, "existing"), requireDate(match, "existing"));
            case "edit" -> MatchInfo.edit(
                    requireNoteId(match, "edit"), requireDate(match, "edit"));
            default -> throw new RejectedException("match.type '" + match.type()
                    + "'는 허용되지 않는다 — new|existing|edit 중 하나여야 한다.");
        };
    }

    private static Long requireNoteId(ProposeRecordArgs.MatchArg match, String type) {
        Long noteId = ValidationSupport.parseNoteId("match.note_id", match.noteId());
        if (noteId == null) {
            throw new RejectedException("match.type=" + type + "인데 note_id가 없다 — 대상 노트 id를 채워라.");
        }
        return noteId;
    }

    // edit의 date는 "고칠 엔트리"를 가리키는 대상 키다 — 없으면 무엇을 고칠지 정해지지 않고, 추측으로
    // 메우면 사용자가 보지 못한 회차가 갈린다(TΔ28a — 폼도 같은 이유로 date를 필수로 잡는다).
    private static LocalDate requireDate(ProposeRecordArgs.MatchArg match, String type) {
        LocalDate date = ValidationSupport.parseDate("match.date", match.date());
        if (date == null) {
            throw new RejectedException("match.type=" + type + "인데 date가 없다 — 대상 날짜(YYYY-MM-DD)를 채워라.");
        }
        return date;
    }
}
