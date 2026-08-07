package com.devwuu.mocha.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TΔ1(changes/0026): eval 케이스 경로 문법 — 필드 하강 + 배열 인덱스만 지원하는 좁은 문법의 계약(ADR-68).
 */
class EvalPathTest {

    @Test
    @DisplayName("AC-Δ1: 필드·인덱스 혼합 경로가 세그먼트로 분해된다")
    void parsesFieldsAndIndexes() {
        EvalPath path = EvalPath.parse("entries[0].brews[12].recipe.grind");

        assertThat(path.raw()).isEqualTo("entries[0].brews[12].recipe.grind");
        assertThat(path.segments()).containsExactly(
                new EvalPath.Segment.Field("entries"),
                new EvalPath.Segment.Index(0),
                new EvalPath.Segment.Field("brews"),
                new EvalPath.Segment.Index(12),
                new EvalPath.Segment.Field("recipe"),
                new EvalPath.Segment.Field("grind"));
    }

    @Test
    @DisplayName("AC-Δ1: snake_case 저장 포맷 필드명을 그대로 받는다 — 판정 대상이 직렬화된 JSON이라서")
    void acceptsSnakeCaseFieldNames() {
        assertThat(EvalPath.parse("entries[0].brews[0].review.my_taste_original").segments())
                .last()
                .isEqualTo(new EvalPath.Segment.Field("my_taste_original"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                       // 빈 경로
            "   ",                    // 공백
            "entries..grind",         // 빈 세그먼트
            "entries[0]..grind",      // 인덱스 뒤 빈 세그먼트
            "[0].brews",              // 루트 인덱스 — 판정 대상이 전부 객체라 문법에 없다
            "entries[].brews",        // 빈 인덱스
            "entries[a].brews",       // 숫자 아닌 인덱스
            "entries[0",              // 닫히지 않은 인덱스
            "entries.brews.",         // 끝나는 점
            "1entries.brews",         // 숫자로 시작하는 필드명
            "entries.brews[0].my taste"  // 공백 포함 필드명
    })
    @DisplayName("AC-Δ1: 문법 위반은 사유와 함께 거부된다 — 오타가 '매치 없음'으로 조용히 넘어가지 않는다")
    void rejectsMalformedPaths(String raw) {
        assertThatThrownBy(() -> EvalPath.parse(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("경로");
    }
}
