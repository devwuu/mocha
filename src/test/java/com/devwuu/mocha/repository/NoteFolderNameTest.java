package com.devwuu.mocha.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ7: 경로 접미 생성기 (ref: changes/0028-rdb-storage/tasks.md TΔ7, delta.md §파일 경로 규약, AC-Δ9).
 *
 * <p>이 시점엔 소비처가 없다 — {@code CardFiles}(TΔ6c)·사진 커밋(TΔ9b)은 식별자 전환 뒤에 붙는다.
 * 그래서 판정을 생성기 단위 테스트로 닫는다: 한글 NFC 왕복 · 금지문자 · 40자 절단 · 로스터리 부재.
 */
class NoteFolderNameTest {

    @Test
    @DisplayName("AC-Δ9: 자모 분리(NFD)로 들어온 한글이 NFC 접미로 수렴한다")
    void normalizesHangulToNfc() {
        String decomposedName = Normalizer.normalize("첼베사", Normalizer.Form.NFD);
        String decomposedRoastery = Normalizer.normalize("커피리브레", Normalizer.Form.NFD);
        // 전제 확인 — NFD가 실제로 다른 문자열이어야 이 테스트가 의미를 갖는다(macOS 파일명 저장 형태).
        assertThat(decomposedName).isNotEqualTo("첼베사");

        String fromDecomposed = NoteFolderName.of(7, decomposedRoastery, decomposedName);

        assertThat(fromDecomposed).isEqualTo("7-커피리브레-첼베사");
        assertThat(Normalizer.isNormalized(fromDecomposed, Normalizer.Form.NFC)).isTrue();
        // 생성·조회가 같은 폴더를 가리키려면 두 표기가 같은 접미로 수렴해야 한다.
        assertThat(fromDecomposed).isEqualTo(NoteFolderName.of(7, "커피리브레", "첼베사"));
    }

    @Test
    @DisplayName("금지문자는 제거하고 공백은 '-'로 접는다")
    void stripsForbiddenCharactersAndFoldsWhitespace() {
        assertThat(NoteFolderName.of(5, "커피 리브레", "콜롬비아 / 게이샤 * \"특별\""))
                .isEqualTo("5-커피-리브레-콜롬비아-게이샤-특별");
        assertThat(NoteFolderName.of(5, null, "A:B\\C<D>E|F?G"))
                .isEqualTo("5-ABCDEFG");
        // 표기가 통째로 사라져도 id는 남는다 — 식별을 보장하는 것은 앞의 id뿐이다.
        assertThat(NoteFolderName.of(9, null, "///")).isEqualTo("9");
    }

    @Test
    @DisplayName("접미 전체가 40자를 넘으면 절단한다")
    void truncatesToMaxLength() {
        String longName = "가나다라마바사아자차".repeat(4); // 40자

        String folder = NoteFolderName.of(12, "커피리브레", longName);

        assertThat(folder).hasSize(NoteFolderName.MAX_LENGTH).startsWith("12-커피리브레-");
    }

    @Test
    @DisplayName("절단면이 세그먼트 경계에 떨어져도 '-'로 끝나지 않는다")
    void doesNotEndWithDashAfterTruncation() {
        String roastery = "가".repeat(37); // "1-" + 37자 = 39 → 40번째 문자가 구분자 '-'

        String folder = NoteFolderName.of(1, roastery, "예가체프");

        assertThat(folder).isEqualTo("1-" + roastery).doesNotEndWith("-");
    }

    @Test
    @DisplayName("절단이 서러게이트 쌍을 쪼개지 않는다")
    void doesNotSplitSurrogatePair() {
        String coffeeName = "가".repeat(37) + "🫘"; // "1-" + 37자 = 39 → 이모지가 39·40번째 코드유닛

        String folder = NoteFolderName.of(1, null, coffeeName);

        assertThat(folder).isEqualTo("1-" + "가".repeat(37));
        assertThat(Character.isHighSurrogate(folder.charAt(folder.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("로스터리가 없으면 세그먼트를 생략한다 — 빈 세그먼트를 만들지 않는다")
    void omitsMissingRoastery() {
        assertThat(NoteFolderName.of(3, null, "예가체프")).isEqualTo("3-예가체프");
        assertThat(NoteFolderName.of(3, "   ", "예가체프")).isEqualTo("3-예가체프");
        assertThat(NoteFolderName.of(3, "///", "예가체프")).isEqualTo("3-예가체프")
                .doesNotContain("--");
    }
}
