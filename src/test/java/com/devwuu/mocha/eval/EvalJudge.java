package com.devwuu.mocha.eval;

import com.devwuu.mocha.domain.PendingNote;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 케이스 기대 계약 판정 — {@link EvalHarness.Run}(사후 상태) 하나만 보고 위반 사유 목록을 만든다
 * (ref: plan.md#ADR-68, changes/0026 TΔ2, AC-Δ3·Δ4).
 *
 * <p>판정 대상은 <b>구조·계약뿐</b>이다: pending diff·tool 호출 시퀀스·검증 거부·노트 무변화. 응답 문구는
 * 보지 않는다 — 같은 계약을 지키면서 문장은 매번 달라지는 것이 정상이고, 문구를 걸면 케이스가 모델 교체마다
 * 무의미하게 빨개진다(백엔드 CLAUDE.md §5.3).
 *
 * <p>단언 실패는 예외가 아니라 <b>사유 문자열 목록</b>이다. 한 회차에서 여러 기대가 동시에 깨지는 일이
 * 흔한데(예: pending 미생성 + tool 미호출), 첫 실패에서 멈추면 실패 회차를 다시 돌려야 원인을 본다 —
 * 실 API 비용이 드는 하네스에서 그 재실행이 제일 비싸다(AC-Δ4).
 */
final class EvalJudge {

    private EvalJudge() {
    }

    /** 위반 사유 목록. 비어 있으면 이 회차는 통과다. */
    static List<String> judge(EvalCase evalCase, EvalHarness.Run run, ObjectMapper mapper) {
        List<String> failures = new ArrayList<>();
        EvalCase.Expect expect = evalCase.expect();
        judgePending(expect.pending(), run, mapper, failures);
        judgeTools(expect.tools(), run, mapper, failures);
        judgeCount("검증 거부 수", expect.rejections(), run.rejectionCount(), failures);
        judgeNotes(expect.unchangedNotes(), run, failures);
        return failures;
    }

    private static void judgePending(EvalCase.Pending expect, EvalHarness.Run run,
                                     ObjectMapper mapper, List<String> failures) {
        if (expect == null) {
            return;
        }
        PendingNote pending = run.pendingNote();
        // "무변화"는 바이트 동일성으로 본다 — 같은 커피로 다시 제안해 내용이 겹치는 경우까지 통과시키지 않는다.
        boolean sameBytes = Objects.equals(run.pendingBefore(), run.pendingAfter());
        switch (expect.state()) {
            case CREATED -> {
                if (pending == null) {
                    failures.add("pending 기대=created — 턴 종료 후 pending이 없다(제안 미수용)");
                } else if (sameBytes) {
                    failures.add("pending 기대=created — pending 파일이 초기 상태와 바이트 동일하다(새 제안 없음)");
                }
            }
            case ABSENT -> {
                if (pending != null) {
                    failures.add("pending 기대=absent — pending이 생성됐다: " + summarize(run.pendingAfter()));
                }
            }
            case UNCHANGED -> {
                if (!sameBytes) {
                    failures.add("pending 기대=unchanged — pending 파일이 바뀌었다"
                            + "\n      before = " + summarize(run.pendingBefore())
                            + "\n      after  = " + summarize(run.pendingAfter()));
                }
            }
        }
        if (pending == null) {
            return; // mode·draft는 pending이 있어야 판정 가능 — 위에서 이미 사유를 남겼다.
        }
        if (expect.mode() != null && expect.mode() != pending.mode()) {
            failures.add("pending.mode 기대=" + expect.mode().json() + " 실제=" + pending.mode().json());
        }
        if (expect.draft() != null) {
            JsonNode draft = mapper.valueToTree(pending.draft());
            for (EvalCase.PathAssertion assertion : expect.draft()) {
                check("pending.draft", assertion, draft, failures);
            }
        }
    }

    private static void judgeTools(EvalCase.Tools expect, EvalHarness.Run run,
                                   ObjectMapper mapper, List<String> failures) {
        if (expect == null) {
            return;
        }
        List<String> actual = run.tools().stream().map(RecordingToolCallbacks.Invocation::name).toList();
        if (expect.sequenceContains() != null && !isSubsequence(expect.sequenceContains(), actual)) {
            failures.add("tool 부분 시퀀스 불일치 — 기대(순서 유지)=" + expect.sequenceContains()
                    + " 실제=" + actual);
        }
        judgeCount("tool 총 호출 수", expect.total(), actual.size(), failures);
        if (expect.calls() == null) {
            return;
        }
        for (EvalCase.Call call : expect.calls()) {
            List<RecordingToolCallbacks.Invocation> matching = run.tools().stream()
                    .filter(i -> i.name().equals(call.name()))
                    .toList();
            judgeCount("tool '" + call.name() + "' 호출 수",
                    new EvalCase.Count(call.min(), call.max()), matching.size(), failures);
            judgeCallArgs(call, matching, mapper, failures);
        }
    }

    // 인자 단언은 "그 이름의 호출 중 최소 하나가 전부 만족"이다 — 자가 정정 케이스처럼 같은 tool을 여러 번
    // 부르는 흐름에서, 거부된 첫 호출까지 기대를 만족해야 한다고 걸면 정정 자체가 실패로 뒤집힌다(FR-25).
    private static void judgeCallArgs(EvalCase.Call call, List<RecordingToolCallbacks.Invocation> matching,
                                      ObjectMapper mapper, List<String> failures) {
        if (call.args() == null || call.args().isEmpty()) {
            return;
        }
        if (matching.isEmpty()) {
            failures.add("tool '" + call.name() + "' 인자 단언 — 호출이 하나도 없다");
            return;
        }
        List<String> best = null;
        for (RecordingToolCallbacks.Invocation invocation : matching) {
            List<String> perCall = new ArrayList<>();
            JsonNode args = parse(invocation.argumentsJson(), mapper);
            for (EvalCase.PathAssertion assertion : call.args()) {
                check("tool '" + call.name() + "' 인자", assertion, args, perCall);
            }
            if (perCall.isEmpty()) {
                return; // 만족한 호출이 하나라도 있으면 통과
            }
            if (best == null || perCall.size() < best.size()) {
                best = perCall; // 가장 근접한 호출의 사유만 보고한다 — 전 호출을 나열하면 원인이 묻힌다
            }
        }
        failures.addAll(best);
    }

    private static void judgeCount(String label, EvalCase.Count expect, long actual, List<String> failures) {
        if (expect == null) {
            return;
        }
        if (expect.min() != null && actual < expect.min()) {
            failures.add(label + " 기대 최소=" + expect.min() + " 실제=" + actual);
        }
        if (expect.max() != null && actual > expect.max()) {
            failures.add(label + " 기대 최대=" + expect.max() + " 실제=" + actual);
        }
    }

    private static void judgeNotes(Boolean expectUnchanged, EvalHarness.Run run, List<String> failures) {
        if (expectUnchanged == null) {
            return;
        }
        boolean unchanged = run.notesBefore().equals(run.notesAfter());
        if (expectUnchanged && !unchanged) {
            failures.add("노트 저장소 기대=무변화 — 노트가 바뀌었다: " + notesDiff(run));
        } else if (!expectUnchanged && unchanged) {
            failures.add("노트 저장소 기대=변화 — 노트가 그대로다");
        }
    }

    private static String notesDiff(EvalHarness.Run run) {
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> after : run.notesAfter().entrySet()) {
            String before = run.notesBefore().get(after.getKey());
            if (before == null) {
                changes.add("+" + after.getKey());
            } else if (!before.equals(after.getValue())) {
                changes.add("~" + after.getKey());
            }
        }
        run.notesBefore().keySet().stream()
                .filter(name -> !run.notesAfter().containsKey(name))
                .forEach(name -> changes.add("-" + name));
        return String.join(", ", changes);
    }

    // ---- 경로 단언 ----

    private static void check(String where, EvalCase.PathAssertion assertion, JsonNode root,
                              List<String> failures) {
        JsonNode node = resolve(root, assertion.parsedPath());
        String at = where + " " + assertion.path();
        if (assertion.present() != null) {
            boolean present = node != null && !node.isNull();
            if (present != assertion.present()) {
                failures.add(at + " 기대 존재=" + assertion.present() + " 실제=" + present);
            }
            return;
        }
        if (assertion.size() != null) {
            if (node == null || !node.isArray()) {
                failures.add(at + " 기대 배열 크기=" + assertion.size() + " 실제=" + describe(node) + "(배열 아님)");
            } else if (node.size() != assertion.size()) {
                failures.add(at + " 기대 배열 크기=" + assertion.size() + " 실제=" + node.size());
            }
            return;
        }
        if (!valueMatches(node, assertion.value())) {
            failures.add(at + " 기대=" + assertion.value() + " 실제=" + describe(node));
        }
    }

    /** 필드 하강 + 배열 인덱스만 — 중간이 없거나 타입이 어긋나면 null(단언이 "없음"으로 실패한다). */
    private static JsonNode resolve(JsonNode root, EvalPath path) {
        JsonNode node = root;
        for (EvalPath.Segment segment : path.segments()) {
            if (node == null || node.isNull()) {
                return null;
            }
            node = switch (segment) {
                case EvalPath.Segment.Field field -> node.get(field.name());
                case EvalPath.Segment.Index index ->
                        node.isArray() && index.index() < node.size() ? node.get(index.index()) : null;
            };
        }
        return node;
    }

    private static boolean valueMatches(JsonNode node, Object expected) {
        if (node == null || node.isNull()) {
            return expected == null;
        }
        // 숫자는 표기가 아니라 값으로 본다 — 10과 10.0이 같은 추출량인데 문자열 비교로 갈리면 안 된다(AC-66).
        if (expected instanceof Number number && node.isNumber()) {
            return Double.compare(node.doubleValue(), number.doubleValue()) == 0;
        }
        if (expected instanceof Boolean bool && node.isBoolean()) {
            return node.booleanValue() == bool;
        }
        return node.asString().equals(String.valueOf(expected));
    }

    private static JsonNode parse(String json, ObjectMapper mapper) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            return null; // 인자 JSON이 깨졌으면 단언이 전부 "없음"으로 실패한다 — 그것이 정확한 관측이다
        }
    }

    private static String describe(JsonNode node) {
        return node == null || node.isNull() ? "없음" : node.toString();
    }

    private static String summarize(Optional<String> json) {
        return json.map(s -> s.length() <= 400 ? s : s.substring(0, 400) + "…(생략)").orElse("없음");
    }

    // 순서만 유지하면 사이에 다른 호출이 끼어도 통과 — 모델이 정보를 더 조회하는 것 자체는 실패가 아니다.
    private static boolean isSubsequence(List<String> expected, List<String> actual) {
        int cursor = 0;
        for (String name : actual) {
            if (cursor < expected.size() && expected.get(cursor).equals(name)) {
                cursor++;
            }
        }
        return cursor == expected.size();
    }
}
