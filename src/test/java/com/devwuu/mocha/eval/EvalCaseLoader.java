package com.devwuu.mocha.eval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * eval 케이스 로더 — {@code <cases-dir>/<id>/case.yaml}을 스캔·파싱·검증한다 (ref: plan.md#ADR-68, AC-Δ1).
 *
 * <p>검증이 로더의 본체다. 케이스는 손으로 쓰는 자산이고 실행에는 실 API 비용이 드니, 오타 하나로 러너가
 * 엉뚱한 걸 판정하거나 조용히 아무것도 판정하지 않는 상태를 여기서 전부 끊는다:
 * <ul>
 *   <li>모르는 필드는 실패 — 오타 난 기대는 "지정 안 함"으로 흘러 <b>초록 거짓말</b>이 된다</li>
 *   <li>빈 기대는 실패 — 아무것도 단언하지 않는 케이스는 비용만 쓰고 통과한다</li>
 *   <li>모순된 기대는 실패 — 없을 제안의 내용을 단언하는 등</li>
 *   <li>참조한 픽스처가 실재하지 않으면 실패 — 초기 상태가 조용히 비는 것을 막는다</li>
 * </ul>
 * 실패 메시지는 항상 케이스 id + 필드 경로 + 사유다(bare rejection 금지, REVIEW.md §6.3 준용).
 *
 * <p>반대로 <b>케이스 디렉터리의 부재·비어 있음은 실패가 아니다</b> — 케이스는 비커밋 개인 데이터라
 * 클론 직후 환경에는 없는 것이 정상이고, 러너가 그 빈 목록을 받아 스킵으로 끝낸다(AC-Δ2).
 */
public final class EvalCaseLoader {

    private static final String CASE_FILE = "case.yaml";

    // 저장 포맷과 같은 snake_case 규칙 — 케이스의 경로 단언이 노트 JSON 필드명을 그대로 쓰기 때문에
    // 케이스 파일 자체도 같은 표기로 통일한다(MochaObjectMapper와 동일 전략, 다른 포맷).
    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            // 모르는 필드 = 오타 난 기대. 조용히 무시되면 케이스가 아무것도 판정하지 않고 초록이 된다.
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // state: created / mode: record 처럼 소문자로 쓰게 둔다(YAML 관례).
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            // today의 원 오프셋(+09:00) 보존 — 고정 시각이 UTC로 정규화되면 케이스가 의도한 로컬 날짜가 흔들린다.
            .configure(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
            .build();

    private EvalCaseLoader() {
    }

    /**
     * 케이스 디렉터리 하위의 케이스 폴더를 전부 로드한다(id 오름차순).
     *
     * @return 케이스 목록. 디렉터리가 없거나 케이스 폴더가 하나도 없으면 빈 목록(실패 아님 — AC-Δ2).
     * @throws EvalCaseFormatException 케이스 폴더 중 하나라도 스키마를 위반하면.
     */
    public static List<EvalCase> loadAll(Path casesDir) {
        if (casesDir == null || !Files.isDirectory(casesDir)) {
            return List.of();
        }
        List<Path> caseDirs;
        try (Stream<Path> children = Files.list(casesDir)) {
            caseDirs = children
                    .filter(Files::isDirectory)
                    // 숨김 폴더는 케이스가 아니다(.git·.DS_Store 부류 — ADR-66의 비자산 파일 제외와 같은 정신).
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("eval 케이스 디렉터리 열람 실패: " + casesDir, e);
        }
        return caseDirs.stream().map(EvalCaseLoader::load).toList();
    }

    /**
     * 케이스 폴더 1건을 로드한다. id는 폴더명이다.
     *
     * @throws EvalCaseFormatException {@code case.yaml} 부재·파싱 실패·스키마 위반.
     */
    public static EvalCase load(Path caseDir) {
        String id = caseDir.getFileName().toString();
        Path file = caseDir.resolve(CASE_FILE);
        if (!Files.isRegularFile(file)) {
            throw new EvalCaseFormatException(
                    id + ": " + CASE_FILE + "이 없다 — 케이스 폴더는 " + CASE_FILE + "을 가져야 한다 (" + file + ")");
        }
        EvalCase parsed;
        try {
            parsed = MAPPER.readValue(file.toFile(), EvalCase.class);
        } catch (JacksonException e) {
            throw new EvalCaseFormatException(
                    id + "/" + CASE_FILE + ": 파싱 실패 — " + e.getOriginalMessage(), e);
        }
        if (parsed == null) {
            throw new EvalCaseFormatException(id + "/" + CASE_FILE + ": 내용이 비었다");
        }
        validate(id, parsed, caseDir);
        return parsed.withIdentity(id, caseDir);
    }

    private static void validate(String id, EvalCase c, Path caseDir) {
        String at = id + "/" + CASE_FILE + ": ";

        // 신원은 폴더가 소유한다 — 파일에도 쓰면 둘이 어긋날 때 무엇이 맞는지 알 수 없다.
        if (c.id() != null || c.dir() != null) {
            throw new EvalCaseFormatException(at + "id·dir — 폴더명이 케이스 id를 소유한다, "
                    + CASE_FILE + "에 쓰지 않는다");
        }
        if (isBlank(c.origin())) {
            throw new EvalCaseFormatException(at + "origin — 필수 필드가 비었다"
                    + " (이 케이스가 어느 관측에서 왔는지: 델타 번호·로그 날짜 등 — ADR-68)");
        }
        if (c.today() == null) {
            throw new EvalCaseFormatException(at + "today — 필수 필드가 비었다"
                    + " (오프셋 포함 고정 시각, 예: \"2026-07-18T09:00:00+09:00\")");
        }
        if (c.utterances() == null || c.utterances().isEmpty()) {
            throw new EvalCaseFormatException(at + "utterances — 발화가 최소 1개 필요하다");
        }
        for (int i = 0; i < c.utterances().size(); i++) {
            if (isBlank(c.utterances().get(i))) {
                throw new EvalCaseFormatException(at + "utterances[" + i + "] — 빈 발화");
            }
        }
        validateInitial(at, c.initial(), caseDir);
        validateExpect(at, c.expect());
    }

    private static void validateInitial(String at, EvalCase.Initial initial, Path caseDir) {
        if (initial == null) {
            return;
        }
        requireFixture(at + "initial.notes", initial.notes(), caseDir, true);
        // draft는 디렉터리가 아니라 파일 하나다 — 폼은 화면에 하나뿐이라(spec §8) 여러 벌을 심을 자리가 없다.
        requireFixture(at + "initial.draft", initial.draft(), caseDir, false);
    }

    /** 동봉 픽스처 참조 검증 — 케이스 폴더 안이어야 하고, 실재해야 한다. */
    private static void requireFixture(String at, String ref, Path caseDir, boolean directory) {
        if (ref == null) {
            return;
        }
        if (ref.isBlank()) {
            throw new EvalCaseFormatException(at + " — 참조가 비었다 (쓰지 않으려면 필드를 지운다)");
        }
        Path base = caseDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(ref).normalize();
        if (!resolved.startsWith(base)) {
            throw new EvalCaseFormatException(at + " — 케이스 폴더 밖을 가리킨다: " + ref
                    + " (픽스처는 케이스 폴더에 동봉한다)");
        }
        boolean ok = directory ? Files.isDirectory(resolved) : Files.isRegularFile(resolved);
        if (!ok) {
            throw new EvalCaseFormatException(at + " — 참조한 " + (directory ? "디렉터리" : "파일")
                    + "가 없다: " + ref);
        }
    }

    private static void validateExpect(String at, EvalCase.Expect expect) {
        if (expect == null) {
            throw new EvalCaseFormatException(at + "expect — 필수 블록이 없다"
                    + " (아무것도 단언하지 않는 케이스는 비용만 쓰고 통과한다)");
        }
        if (expect.proposal() == null && expect.tools() == null
                && expect.rejections() == null && expect.unchangedNotes() == null) {
            throw new EvalCaseFormatException(at + "expect — 기대가 하나도 없다"
                    + " (proposal·tools·rejections·unchanged_notes 중 최소 1종)");
        }
        validateProposal(at + "expect.proposal", expect.proposal());
        validateTools(at + "expect.tools", expect.tools());
        validateCount(at + "expect.rejections", expect.rejections());
    }

    private static void validateProposal(String at, EvalCase.Proposal proposal) {
        if (proposal == null) {
            return;
        }
        if (proposal.state() == null) {
            throw new EvalCaseFormatException(at + ".state — 필수 필드가 비었다 (created|absent)");
        }
        if (proposal.state() == EvalCase.Proposal.State.ABSENT
                && proposal.draft() != null && !proposal.draft().isEmpty()) {
            throw new EvalCaseFormatException(at + " — state=absent인데 draft 단언이 있다"
                    + " (없을 제안의 내용은 단언할 수 없다)");
        }
        // match도 같은 모순이다 — 없을 제안의 매칭 판정을 단언할 수 없다(TΔ21).
        if (proposal.state() == EvalCase.Proposal.State.ABSENT
                && proposal.match() != null && !proposal.match().isEmpty()) {
            throw new EvalCaseFormatException(at + " — state=absent인데 match 단언이 있다"
                    + " (없을 제안의 매칭 판정은 단언할 수 없다)");
        }
        validateAssertions(at + ".draft", proposal.draft());
        validateAssertions(at + ".match", proposal.match());
    }

    private static void validateTools(String at, EvalCase.Tools tools) {
        if (tools == null) {
            return;
        }
        if (tools.sequenceContains() == null && tools.total() == null && tools.calls() == null) {
            throw new EvalCaseFormatException(at + " — 기대가 하나도 없다"
                    + " (sequence_contains·total·calls 중 최소 1종)");
        }
        if (tools.sequenceContains() != null) {
            if (tools.sequenceContains().isEmpty()) {
                throw new EvalCaseFormatException(at + ".sequence_contains — 빈 시퀀스는 아무것도 판정하지 않는다");
            }
            for (int i = 0; i < tools.sequenceContains().size(); i++) {
                if (isBlank(tools.sequenceContains().get(i))) {
                    throw new EvalCaseFormatException(at + ".sequence_contains[" + i + "] — 빈 tool 이름");
                }
            }
        }
        validateCount(at + ".total", tools.total());
        if (tools.calls() != null) {
            if (tools.calls().isEmpty()) {
                throw new EvalCaseFormatException(at + ".calls — 빈 목록은 아무것도 판정하지 않는다");
            }
            for (int i = 0; i < tools.calls().size(); i++) {
                validateCall(at + ".calls[" + i + "]", tools.calls().get(i));
            }
        }
    }

    private static void validateCall(String at, EvalCase.Call call) {
        if (call == null || isBlank(call.name())) {
            throw new EvalCaseFormatException(at + ".name — 필수 필드가 비었다 (tool 이름)");
        }
        boolean hasArgs = call.args() != null && !call.args().isEmpty();
        if (call.min() == null && call.max() == null && !hasArgs) {
            throw new EvalCaseFormatException(at + " — 기대가 하나도 없다 (min·max·args 중 최소 1종)");
        }
        checkRange(at, call.min(), call.max());
        validateAssertions(at + ".args", call.args());
    }

    private static void validateCount(String at, EvalCase.Count count) {
        if (count == null) {
            return;
        }
        if (count.min() == null && count.max() == null) {
            throw new EvalCaseFormatException(at + " — min·max 중 최소 1개가 필요하다");
        }
        checkRange(at, count.min(), count.max());
    }

    private static void checkRange(String at, Integer min, Integer max) {
        if (min != null && min < 0) {
            throw new EvalCaseFormatException(at + ".min — 음수는 쓸 수 없다: " + min);
        }
        if (max != null && max < 0) {
            throw new EvalCaseFormatException(at + ".max — 음수는 쓸 수 없다: " + max);
        }
        if (min != null && max != null && min > max) {
            throw new EvalCaseFormatException(at + " — min(" + min + ")이 max(" + max + ")보다 크다");
        }
    }

    private static void validateAssertions(String at, List<EvalCase.PathAssertion> assertions) {
        if (assertions == null) {
            return;
        }
        if (assertions.isEmpty()) {
            throw new EvalCaseFormatException(at + " — 빈 목록은 아무것도 판정하지 않는다"
                    + " (쓰지 않으려면 필드를 지운다)");
        }
        for (int i = 0; i < assertions.size(); i++) {
            validateAssertion(at + "[" + i + "]", assertions.get(i));
        }
    }

    private static void validateAssertion(String at, EvalCase.PathAssertion assertion) {
        if (assertion == null || isBlank(assertion.path())) {
            throw new EvalCaseFormatException(at + ".path — 필수 필드가 비었다"
                    + " (예: tasting_days[0].cups[0].recipe.grind)");
        }
        try {
            EvalPath.parse(assertion.path());
        } catch (IllegalArgumentException e) {
            throw new EvalCaseFormatException(at + ".path — " + e.getMessage(), e);
        }
        List<String> given = new ArrayList<>();
        if (assertion.value() != null) {
            given.add("value");
        }
        if (assertion.size() != null) {
            given.add("size");
        }
        if (assertion.present() != null) {
            given.add("present");
        }
        if (assertion.blank() != null) {
            given.add("blank");
        }
        if (given.size() != 1) {
            throw new EvalCaseFormatException(at + " — 단언은 value·size·present·blank 중 정확히 1개여야 한다"
                    + " (지금: " + (given.isEmpty() ? "없음" : String.join("·", given)) + ")");
        }
        // 스칼라만 비교한다 — 중첩 구조 비교는 "무엇이 달랐나"가 흐려져서, 경로를 더 내려 쓰게 한다.
        if (assertion.value() instanceof Map || assertion.value() instanceof List) {
            throw new EvalCaseFormatException(at + ".value — 스칼라만 비교한다"
                    + " (배열 크기는 size, 중첩 값은 경로를 더 내려서 단언한다)");
        }
        if (assertion.size() != null && assertion.size() < 0) {
            throw new EvalCaseFormatException(at + ".size — 음수는 쓸 수 없다: " + assertion.size());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
