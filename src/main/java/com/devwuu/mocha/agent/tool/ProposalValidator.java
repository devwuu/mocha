package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * 제안 tool 2종({@code propose_record}/{@code propose_edit}) 인자의 서버 검증 — strict schema가
 * 보장 못 하는 <b>값 수준 규칙</b>을 결정론으로 강제한다 (ref: specs/coffee-note-agent/data-model.md#5,
 * plan#ADR-45, changes/0018 TΔ1).
 * <ul>
 *   <li>V-1 rating 4범주, V-5 source enum 제약, V-8 recipe 정규화, V-11 my_taste 병존</li>
 *   <li>V-10 날짜 이동 충돌 계산(edit), 단일 대기 거부(FR-22/AC-30)</li>
 * </ul>
 * <p>POLICY: 제안 tool의 서버 검증 실패는 오류 사유를 tool 결과로 반환 — 조용한 드롭·서버 대행 금지
 * (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-9). 순수 도메인 계층 — SDK·I/O 무관, 대상 노트
 * 리졸브(slug → Note)와 pending 조회는 호출부(tool 구현, TΔ6)의 몫이다.
 */
public class ProposalValidator {

    // V-5: coffee_name은 검색 보강 대상이 아니다 — 검색 앵커이자 정체성 (ref: data-model.md#2.1).
    private static final Set<Source> COFFEE_NAME_SOURCES = Set.of(Source.USER, Source.PHOTO);
    private static final Set<Source> ENRICHABLE_SOURCES = Set.of(Source.USER, Source.PHOTO, Source.SEARCH);

    /**
     * {@code propose_record} 검증 — 통과 시 도메인 타입으로 정규화된 {@link RecordProposal}.
     *
     * @param args    strict schema를 통과한 미검증 인자.
     * @param pending 현재 확인 대기 — 없으면 null. 단일 대기 판정 입력.
     */
    public ToolValidation<RecordProposal> validateRecord(ProposeRecordArgs args, PendingNote pending) {
        try {
            Sourced<String> coffeeName = sourced("coffee_name", args.coffeeName(), COFFEE_NAME_SOURCES);
            if (coffeeName == null) {
                throw new RejectedException("coffee_name이 비어 있다 — 커피 이름은 기록의 정체성이라 없으면 "
                        + "기록을 만들 수 없다. 사용자에게 커피 이름을 물어봐라.");
            }
            Sourced<String> roastery = sourced("roastery", args.roastery(), ENRICHABLE_SOURCES);
            requireUpdatableOrFree(pending, coffeeName.value(), roastery == null ? null : roastery.value());
            Sourced<String> origin = sourced("origin", args.origin(), ENRICHABLE_SOURCES);
            Sourced<String> process = sourced("process", args.process(), ENRICHABLE_SOURCES);
            Sourced<String> roastLevel = sourced("roast_level", args.roastLevel(), ENRICHABLE_SOURCES);
            Sourced<List<String>> officialNotes = sourcedNotes(args.officialNotes());

            LocalDate targetDate = parseDate("target_date", args.targetDate());
            if (targetDate == null) {
                throw new RejectedException("target_date가 없다 — 시음일(YYYY-MM-DD)을 채워라. "
                        + "상대 날짜(\"어제\")는 컨텍스트의 today 기준으로 해석해 절대 날짜로 보내라.");
            }
            MatchInfo match = toMatchInfo(args.match());

            String myTaste = blankToNull(args.myTaste());
            List<String> sources = dropBlanks(args.sources());
            NoteMeta meta = new NoteMeta(coffeeName, roastery, origin, process, roastLevel, officialNotes, sources);
            return ToolValidation.ok(new RecordProposal(
                    meta, targetDate, myTaste, originalOf(myTaste, args.myTasteOriginal()),
                    parseRating(args.rating()), normalizeRecipe(args.recipe()), match));
        } catch (RejectedException rejection) {
            return ToolValidation.rejected(rejection.getMessage());
        }
    }

    /**
     * {@code propose_edit} 검증 — 통과 시 도메인 타입으로 정규화된 {@link EditProposal}(V-10 충돌 계산 포함).
     *
     * @param args    strict schema를 통과한 미검증 인자.
     * @param note    slug로 리졸브된 대상 노트 — 미존재 오류는 호출부(tool 구현)가 이미 반환했다.
     * @param pending 현재 확인 대기 — 없으면 null. 단일 대기 판정 입력.
     */
    public ToolValidation<EditProposal> validateEdit(ProposeEditArgs args, Note note, PendingNote pending) {
        try {
            LocalDate targetDate = parseDate("date", args.date());
            if (targetDate == null) {
                throw new RejectedException("date가 없다 — 수정 대상 엔트리의 날짜(YYYY-MM-DD)를 채워라.");
            }
            // 환각 필터(get_note 미존재 오류와 같은 정신): 실존하지 않는 엔트리를 대상으로 수정 세션이 열리지 않게.
            if (note.entries().stream().noneMatch(e -> targetDate.equals(e.date()))) {
                throw new RejectedException("노트 '" + note.slug() + "'에는 " + targetDate
                        + " 시음 엔트리가 없다 — get_note로 실제 엔트리 날짜를 확인해라.");
            }
            requireSameEditTargetOrFree(pending, note.slug(), targetDate);

            ProposeEditArgs.Patch patch = args.patch() == null ? ProposeEditArgs.Patch.empty() : args.patch();
            Sourced<String> roastery = sourced("roastery", patch.roastery(), ENRICHABLE_SOURCES);
            Sourced<String> origin = sourced("origin", patch.origin(), ENRICHABLE_SOURCES);
            Sourced<String> process = sourced("process", patch.process(), ENRICHABLE_SOURCES);
            Sourced<String> roastLevel = sourced("roast_level", patch.roastLevel(), ENRICHABLE_SOURCES);
            Sourced<List<String>> officialNotes = sourcedNotes(patch.officialNotes());

            // 대상 자신의 날짜로는 이동이 아니다 — null로 정규화(구 수정 flow의 충돌 판정 승계).
            LocalDate newDate = parseDate("new_date", patch.newDate());
            if (targetDate.equals(newDate)) {
                newDate = null;
            }
            // V-10: 이동처에 대상 노트의 기존 엔트리가 있으면 충돌 — 미리보기 덮어쓰기 경고의 근거로 서버가 계산한다.
            LocalDate movedTo = newDate;
            boolean dateConflict = movedTo != null
                    && note.entries().stream().anyMatch(e -> movedTo.equals(e.date()));

            String myTaste = blankToNull(patch.myTaste());
            return ToolValidation.ok(new EditProposal(
                    note.slug(), targetDate, roastery, origin, process, roastLevel, officialNotes,
                    myTaste, originalOf(myTaste, patch.myTasteOriginal()),
                    parseRating(patch.rating()), normalizeRecipe(patch.recipe()), newDate, dateConflict));
        } catch (RejectedException rejection) {
            return ToolValidation.rejected(rejection.getMessage());
        }
    }

    // POLICY: 확인 대기 중 새 기록 제안은 서버가 거부한다 — 단일 대기 원칙 (ref: plan.md#ADR-45, AC-30).
    //         record pending 존재 중 "같은 커피"의 재호출만 FR-5 갱신 경로로 통과시킨다. 같은 커피 판정 =
    //         정규화(소문자화·공백 제거, V-13 기준) coffee_name+roastery 동일 — 싱글 오리진은 이름이
    //         로스터리 간 겹치기 쉬워 이름만으로는 부족하다(ADR-37 정체성 기준과 동일, 사용자 확정
    //         2026-07-17). 단 한쪽 roastery가 비어 있으면 이름만 대조한다 — 모름은 다름이 아니며,
    //         대기 중 roastery 보강·정정 갱신을 막지 않는다 (ref: data-model.md#3.3).
    private static void requireUpdatableOrFree(PendingNote pending, String proposedCoffeeName,
                                               String proposedRoastery) {
        if (pending == null) {
            return;
        }
        if (pending.mode() == PendingNote.Mode.RECORD
                && sameNormalized(proposedCoffeeName, pending.draft().coffeeName().value())
                && roasteryCompatible(proposedRoastery, pending.draft().roastery())) {
            return; // FR-5: 대기 중 수정 발화 = 같은 커피의 propose_record 재호출 → 갱신 경로.
        }
        throw new RejectedException(pendingBlocksReason(pending));
    }

    private static boolean sameNormalized(String a, String b) {
        return Aliases.normalize(a).equals(Aliases.normalize(b));
    }

    // 같은 커피 판정의 roastery 축 — 양쪽 다 있을 때만 대조하고, 한쪽이 비면 이름 대조에 맡긴다.
    private static boolean roasteryCompatible(String proposed, Sourced<String> draftRoastery) {
        String draft = draftRoastery == null ? null : draftRoastery.value();
        if (Aliases.normalize(proposed).isEmpty() || Aliases.normalize(draft).isEmpty()) {
            return true;
        }
        return sameNormalized(proposed, draft);
    }

    // POLICY: 단일 대기 원칙 준용(record든 edit든) — 확인 대기 중 다른 대상의 수정 세션은 거부한다.
    //         같은 대상(slug+date)의 propose_edit 재호출만 FR-5 후속 수정으로 통과시킨다
    //         (ref: plan.md#ADR-45, 구 수정 진입 거부의 승계).
    private static void requireSameEditTargetOrFree(PendingNote pending, String slug, LocalDate targetDate) {
        if (pending == null) {
            return;
        }
        if (pending.mode() == PendingNote.Mode.EDIT
                && pending.target() != null
                && pending.target().slug().equals(slug)
                && pending.target().date().equals(targetDate)) {
            return; // FR-5: 수정 세션의 후속 수정 발화 = 같은 대상의 propose_edit 재호출 → 갱신 경로.
        }
        throw new RejectedException(pendingBlocksReason(pending));
    }

    private static String pendingBlocksReason(PendingNote pending) {
        String current = pending.mode() == PendingNote.Mode.EDIT
                ? "수정 세션(" + pending.target().slug() + ")"
                : "새 기록(" + pending.draft().coffeeName().value() + ")";
        return "확인 대기 중인 " + current + "이 이미 있다 — 단일 대기 원칙상 다른 제안을 받을 수 없다. "
                + "사용자에게 먼저 [저장]이나 [취소]로 마무리해 달라고 안내해라.";
    }

    /**
     * 출처 표시 인자 → 도메인 {@link Sourced} (V-5). value가 비면 null로 정규화하고,
     * value가 있는데 source가 없거나 허용 밖이면 사유와 함께 거부한다.
     */
    private static Sourced<String> sourced(String field, SourcedArg<String> arg, Set<Source> allowed) {
        if (arg == null || blankToNull(arg.value()) == null) {
            return null;
        }
        return new Sourced<>(arg.value().strip(), parseSource(field, arg.source(), allowed));
    }

    /** official_notes 변형 — 항목의 공백을 걷어내고 전무면 null (V-5는 동일 적용). */
    private static Sourced<List<String>> sourcedNotes(SourcedArg<List<String>> arg) {
        if (arg == null) {
            return null;
        }
        List<String> notes = dropBlanks(arg.value());
        if (notes.isEmpty()) {
            return null;
        }
        return new Sourced<>(notes, parseSource("official_notes", arg.source(), ENRICHABLE_SOURCES));
    }

    private static Source parseSource(String field, String raw, Set<Source> allowed) {
        if (raw == null) {
            throw new RejectedException(field + "의 source가 없다 — 값을 채웠으면 출처(" +
                    allowedLabels(allowed) + ")를 함께 보고해라(V-5).");
        }
        Source source;
        try {
            source = Source.from(raw);
        } catch (IllegalArgumentException e) {
            throw new RejectedException(field + "의 source '" + raw + "'는 허용되지 않는다 — "
                    + allowedLabels(allowed) + " 중 하나여야 한다(V-5).");
        }
        if (!allowed.contains(source)) {
            throw new RejectedException(field + "의 source '" + raw + "'는 허용되지 않는다 — "
                    + allowedLabels(allowed) + " 중 하나여야 한다(V-5).");
        }
        return source;
    }

    private static String allowedLabels(Set<Source> allowed) {
        // Set 순회 순서 비결정 방지 — 선언 순서(user, photo, search)로 고정해 사유 문구를 결정론으로.
        StringBuilder labels = new StringBuilder();
        for (Source source : Source.values()) {
            if (allowed.contains(source)) {
                labels.append(labels.isEmpty() ? "" : "|").append(source.json());
            }
        }
        return labels.toString();
    }

    // V-1: rating ∈ 4범주 enum 또는 null — 위반 시 오류 사유를 tool 결과로 반환해 루프 안에서 정정(AC-9).
    private static Rating parseRating(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Rating.from(raw);
        } catch (IllegalArgumentException e) {
            throw new RejectedException("rating '" + raw + "'는 4범주를 벗어난다 — "
                    + "완전 내스타일|맛있다|맛은 있는데 내스타일은 아님|맛이 없다 중 하나이거나, 미언급이면 null이어야 한다(V-1).");
        }
    }

    // V-8: 위반 값(음수·0·공백)은 항목만 드롭, 3항목 전무면 recipe 자체가 null — 저장 거부 아님(부속 정보).
    private static Recipe normalizeRecipe(Recipe raw) {
        if (raw == null) {
            return null;
        }
        return Recipe.normalize(raw.doseG(), raw.waterMl(), raw.grind());
    }

    // V-11: my_taste가 있으면 my_taste_original도 병존 — 원문 누락 시 정규화본을 양쪽에(감상 유실 방지 우선).
    private static String originalOf(String myTaste, String rawOriginal) {
        if (myTaste == null) {
            return null; // 감상이 없으면 원문만 따로 두지 않는다 — 항상 병존(V-11).
        }
        String original = blankToNull(rawOriginal);
        return original != null ? original : myTaste;
    }

    private static MatchInfo toMatchInfo(ProposeRecordArgs.MatchArg match) {
        if (match == null || match.type() == null) {
            throw new RejectedException("match가 없다 — 신규면 {\"type\":\"new\"}, 기존 노트 대상이면 "
                    + "{\"type\":\"existing\",\"slug\":...,\"date\":...}를 채워라.");
        }
        return switch (match.type()) {
            case "new" -> MatchInfo.newNote();
            case "existing" -> {
                if (blankToNull(match.slug()) == null) {
                    throw new RejectedException("match.type=existing인데 slug가 없다 — 대상 노트 slug를 채워라.");
                }
                LocalDate date = parseDate("match.date", match.date());
                if (date == null) {
                    throw new RejectedException("match.type=existing인데 date가 없다 — 대상 날짜(YYYY-MM-DD)를 채워라.");
                }
                yield MatchInfo.existing(match.slug(), date);
            }
            default -> throw new RejectedException("match.type '" + match.type()
                    + "'는 허용되지 않는다 — new|existing 중 하나여야 한다.");
        };
    }

    // V-3 형식 준용: 날짜 인자는 YYYY-MM-DD — 형식 위반은 사유와 함께 거부(에이전트가 절대 날짜로 정정).
    private static LocalDate parseDate(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new RejectedException(field + " '" + raw + "'는 날짜 형식이 아니다 — YYYY-MM-DD로 보내라. "
                    + "상대 날짜는 컨텍스트의 today 기준으로 해석해라.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static List<String> dropBlanks(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
    }

    /** 검증 거부 — 사유를 담아 {@link ToolValidation.Rejected}로 수렴하는 내부 신호. */
    private static final class RejectedException extends RuntimeException {
        RejectedException(String reason) {
            super(reason);
        }
    }
}
