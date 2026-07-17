package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Aliases;

/**
 * 별칭 생성 경계 — 신규 노트 첫 [저장] 커밋 시 커피명·로스터리의 한국어 음차·이표기를 1콜로 생성한다
 * (ref: plan.md#ADR-37, data-model.md#4.1, spec FR-14; changes/0016).
 * <p>노트당 평생 1회(match=NEW일 때만) 호출된다 — 이후 같은 노트로 매칭된 기록의 관측 표기는 콜 없이
 * 서버가 축적한다(data-model §2.1). 에이전트 루프 밖 보조 콜 경계로 {@code VisionClient}와 함께 별도
 * 유지된다(plan ADR-44·50, NFR-4). 구현: {@link com.devwuu.mocha.llm.OpenAiAliasGenerator}
 * (모델은 텍스트 전용 최경량 전용 키 {@code mocha.alias.model} — ADR-50, changes/0018).
 * <p>POLICY: 별칭 생성 콜 실패·스키마 위반은 저장을 되돌리지 않는다 — 빈 배열({@link Aliases#empty()})로
 * 수렴하고 노트 저장은 유지한다. 이후 관측 표기 축적이 보완한다 (ref: plan.md §7, V-13, ADR-37).
 */
public interface AliasGenerator {

    /**
     * 커피명·로스터리로 별칭을 생성한다 — 신규 노트 첫 커밋 시 1회.
     *
     * @param coffeeName 커밋된 커피명(원문 표시값). null이면 대조 재료가 없으므로 빈 값으로 조립된다.
     * @param roastery   커밋된 로스터리(원문 표시값). null 허용.
     * @return 생성된 {@link Aliases}. 호출·스키마 실패 시 {@link Aliases#empty()}(저장 거부 아님, plan §7).
     */
    Aliases generate(String coffeeName, String roastery);
}
