package com.devwuu.mocha.agent.turn;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.SearchQuery;
import com.devwuu.mocha.llm.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 제안 draft의 <b>검색 보강</b> — 턴이 수거한 제안에서 {@code official_notes}·{@code beans}가 비어 있으면
 * 웹 검색 1콜로 빈 필드만 채운다 (ref: changes/0029-app-interface delta.md#D-16, tasks TΔ24b;
 * spec FR-3/AC-9, plan.md#ADR-49 개정).
 *
 * <p><b>왜 결정론 단계인가</b>: 구 파이프라인 [4] {@code NoteEnricher}는 <i>순서상 무조건</i> 실행되며
 * 검색을 매번 불렀는데, ADR-49가 이를 폐기하고 <i>모델 재량 tool</i>({@code web_search})로 바꿨다. 정책
 * 문장도 배선도 살아 있었고 빠진 것은 «반드시 실행된다»는 성질 하나였다 — 모델은 부르지 않기로 했고 그
 * 선택이 조용히 쌓여 실사용 11턴 검색 0회, {@code SEARCH} 출처 0건으로 나타났다(D-16 ①·②). 하네스를
 * 확률적 영역에서 결정론적 영역으로 되돌리는 것이 이 클래스다 — 여기서 틀리면 그냥 버그이고 테스트가 잡는다.
 *
 * <p><b>구 판본과 다른 점 — 트리거가 한 단계 앞으로 왔다</b>: 구 {@code NoteEnricher}는 <i>검색을 항상 하고
 * 채우기만 조건부</i>였다. 여기서는 채울 것이 있는지를 <b>먼저</b> 보고 없으면 콜 자체를 하지 않는다 —
 * 2턴째 <i>"로스팅은 미디엄이었어"</i> 류가 낭비 콜을 만들지 않는다(D-16 ③).
 *
 * <p><b>자리는 {@code TurnRunner}의 제안 수거 «후»다</b>: tool은 값만 만들고(계층이 커지지 않는다), 외부
 * IO는 이미 OCR·세그먼터 LLM 콜을 소유한 턴 오케스트레이션 층에 선다(백엔드 CLAUDE.md §2). 저장이 아니라
 * <b>제안</b> 시점이어야 보강 값이 폼에 채워져 도착해 사용자가 확인·정정할 수 있다(FR-12).
 * 대가는 D-16 ④가 인지·수용한다 — 보강 값은 모델의 최종 응답 <i>텍스트</i>에는 반영되지 않는다(루프가 이미 끝났다).
 *
 * <p>POLICY: 빈 필드만 채운다 — 값이 이미 있으면(사용자가 말했든 사진 OCR이 읽었든) 검색 값으로 덮지
 * 않는다. 출처로 분기하지 않고 <b>값 유무만</b> 보므로 출처 우선순위 {@code user > photo > search}(V-6)가
 * 자동으로 충족된다 — 구 {@code NoteEnricher}가 가졌던 성질을 그대로 승계한다
 * (ref: specs/coffee-note-agent/data-model.md#V-6, spec AC-3/AC-27).
 * <p>POLICY: {@code coffee_name}은 보강 대상이 아니라 <b>검색 앵커</b>다 — 그대로 통과시키고, 앵커가 없으면
 * 검색 자체를 하지 않는다(V-5: coffee_name의 source는 {user, photo} 한정).
 * <p>POLICY: 검색 실패·무결과는 draft를 그대로 통과시킨다 — 검색 때문에 기록이 막히지 않는다
 * (ref: spec AC-12, plan §7). {@link SearchClient} 계약이 이미 빈 결과로 수렴시키지만, 이 시점은 사용자의
 * 제안이 이미 만들어진 뒤라 예외 하나가 턴 전체를 잃게 하므로 한 겹 더 받는다.
 * <p>POLICY: agent/tool/은 tool 정의·인자·검증만 — 턴 전·후처리 협력자는 agent/turn/에, 새 인터페이스 없이
 * 구체 클래스로 (ref: plan.md#ADR-64). {@link TurnPhotoOcr}(루프 전 전처리)와 대칭이다.
 */
public class TurnProposalEnricher {

    private static final Logger log = LoggerFactory.getLogger(TurnProposalEnricher.class);

    private final SearchClient searchClient;

    public TurnProposalEnricher(SearchClient searchClient) {
        this.searchClient = searchClient;
    }

    /**
     * 이번 턴의 제안을 검색으로 보강한 사본을 돌려준다. 발동하지 않거나 채울 것이 없으면 인자를 그대로 돌려준다.
     *
     * @param proposal 턴이 수거한 제안({@code TurnProposalSink}) — 제안 없는 턴은 null이고 그대로 통과한다.
     */
    public TurnDraft enrich(TurnDraft proposal) {
        if (proposal == null) {
            return null;
        }
        Note note = proposal.note();
        if (!needsEnrichment(note)) {
            return proposal;
        }
        String coffeeName = Sourced.valueOrNull(note.coffeeName());
        if (coffeeName == null || coffeeName.isBlank()) {
            // 검색 앵커 부재 — 무엇에 대한 커피인지 모르는 채로 검색하면 동일성 가드가 성립하지 않는다.
            log.info("검색 보강 건너뜀(커피명 없음) — 제안 그대로 진행");
            return proposal;
        }
        String roastery = Sourced.valueOrNull(note.roastery());

        SearchResult result;
        try {
            result = searchClient.search(new SearchQuery(coffeeName, roastery));
        } catch (RuntimeException e) {
            // SearchClient 계약상 도달하지 않아야 하는 경로다 — 구현체가 empty()로 수렴시킨다. 그럼에도
            // 받는 이유는 여기서 새면 이미 만들어진 제안이 통째로 사라지기 때문이다(AC-12의 취지).
            log.warn("검색 보강 실패 — 제안 그대로 진행: coffee={}", coffeeName, e);
            return proposal;
        }

        Note enriched = fill(note, result);
        if (enriched.equals(note)) {
            log.info("검색 보강 채운 값 없음 — 제안 그대로 진행: coffee={}", coffeeName);
            return proposal;
        }
        log.info("검색 보강 반영: coffee={} roastery={} beans={} roast_level={} official_notes={} sources={}",
                coffeeName,
                changed(note.roastery(), enriched.roastery()),
                note.beans().isEmpty() && !enriched.beans().isEmpty(),
                changed(note.roastLevel(), enriched.roastLevel()),
                Sourced.valuesOrEmpty(note.officialNotes()).isEmpty()
                        && !Sourced.valuesOrEmpty(enriched.officialNotes()).isEmpty(),
                enriched.sources().size() - size(note.sources()));
        return new TurnDraft(enriched, proposal.match());
    }

    // POLICY: 발동 조건은 official_notes·beans 중 하나라도 빈 경우다 — "채울 것이 있을 때만 돈다"
    //         (ref: changes/0029 delta.md#D-16 ③). roast_level은 트리거에서 뺀다(사용자 확정 2026-08-02):
    //         콜을 더 줄이는 선택이고, 대가는 두 축이 이미 찬 노트에서 roast_level이 빈 채로 남아 폼에서
    //         손으로 채워진다는 것이다. 넓히려면 관측 후 D-16 ③을 개정한다(루트 CLAUDE.md §4 right-sizing).
    private static boolean needsEnrichment(Note note) {
        return note.beans().isEmpty() || Sourced.valuesOrEmpty(note.officialNotes()).isEmpty();
    }

    // 빈 필드만 채운 사본. 채울 것이 하나도 없으면 컴포넌트가 전부 같아 equals로 무변화가 드러난다.
    private static Note fill(Note note, SearchResult result) {
        return new Note(
                note.id(),
                note.coffeeName(),                                   // 앵커 — 검색이 만들지 않는다(V-5)
                fill(note.roastery(), result.roastery()),
                fillBeans(note.beans(), result.beans()),
                fill(note.roastLevel(), result.roastLevel()),
                fillNotes(note.officialNotes(), result.officialNotes()),
                note.aliases(),
                mergeSources(note.sources(), result.sources()),
                note.entries(),
                note.createdAt(),
                note.updatedAt());
    }

    // 값이 이미 있으면(source=user·photo 모두) 그대로 — 빈 필드만 채운다(V-6).
    private static Sourced<String> fill(Sourced<String> current, String searched) {
        if (hasText(current)) {
            return current;
        }
        if (searched == null || searched.isBlank()) {
            return current;   // 채울 것이 없으면 원래 상태(보통 null) 유지 → draft 그대로 통과(AC-12)
        }
        return new Sourced<>(searched.strip(), Source.SEARCH);
    }

    // beans는 요소 단위 병합을 하지 않는다 — 사용자·사진이 구성한 배열이 있으면 통째로 유지하고, 비어 있을
    // 때만 검색 결과로 채운다. 요소에 안정적인 동일성 키가 없어(설명 자유 텍스트) 부분 병합이 성립하지 않는다.
    private static List<Bean> fillBeans(List<Bean> current, List<SearchResult.Bean> searched) {
        if (!current.isEmpty() || searched.isEmpty()) {
            return current;
        }
        return Bean.normalize(searched.stream()
                .map(b -> new Bean(
                        new Sourced<>(b.description(), Source.SEARCH),
                        b.process() == null ? null : new Sourced<>(b.process(), Source.SEARCH)))
                .toList());
    }

    // official_notes: 이미 값이 있으면(사용자가 불러줬거나 사진 OCR이 읽은 경우 등) 유지, 없으면 검색 결과로 채움(V-6).
    private static Sourced<List<String>> fillNotes(Sourced<List<String>> current, List<String> searched) {
        if (!Sourced.valuesOrEmpty(current).isEmpty() || searched.isEmpty()) {
            return current;
        }
        return new Sourced<>(List.copyOf(searched), Source.SEARCH);
    }

    // 검색 참조 링크를 기존 sources 뒤에 병합하되 순서를 유지하며 중복 제거(FR-12).
    private static List<String> mergeSources(List<String> current, List<String> searched) {
        List<String> merged = new ArrayList<>();
        if (current != null) {
            merged.addAll(current);
        }
        for (String s : searched) {
            if (s != null && !s.isBlank() && !merged.contains(s)) {
                merged.add(s);
            }
        }
        return List.copyOf(merged);
    }

    private static boolean hasText(Sourced<String> field) {
        return field != null && field.value() != null && !field.value().isBlank();
    }

    private static boolean changed(Sourced<String> before, Sourced<String> after) {
        return !hasText(before) && hasText(after);
    }

    // Note.sources는 도메인 생성자가 정규화하지 않는 배열이라 null이 올 수 있다(관측 계산 전용 null 안전).
    private static int size(List<String> list) {
        return list == null ? 0 : list.size();
    }
}
