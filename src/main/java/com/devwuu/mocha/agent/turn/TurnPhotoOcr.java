package com.devwuu.mocha.agent.turn;

import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 이번 턴에 실린 사진을 읽어 OCR 결과로 바꾼다 — 루프 전 <b>결정론 전처리</b>
 * (ref: plan.md §1 [2.5]·#ADR-23·#ADR-44, spec FR-19; changes/0029 tasks.md TΔ8a, delta.md#D-11).
 *
 * <p><b>병합하지 않는다.</b> 여기가 내놓는 것은 <i>사진에서 읽은 재료</i>이고, 그것을 발화·검색 결과와
 * 합쳐 필드를 채우는 일은 모델이 루프 안에서 한다(통합 규칙은 {@code AgentSystemPrompt}가 소유하고
 * 출처 우선순위 {@code user > photo > search}가 그 규칙이다). D-11이 <i>"클라이언트가 병합"</i>과
 * <i>"서버가 병합해 draft를 돌려줌"</i>을 함께 기각한 근거가 이것이다 — 규칙을 결정론 코드로 다시 쓰면
 * 프롬프트와 이중화된다.
 *
 * <p><b>턴에 실린 이름으로 고른다</b>: 스테이징에는 저장·취소 전까지 올린 사진이 누적되지만, 이번 메시지에
 * 묶인 것은 클라이언트가 ＋로 첨부한 것뿐이다(D-11 — <i>"사용자가 직접 묶는다"</i>). 전부 읽으면 앞선
 * 메시지의 사진이 매 턴 다시 OCR에 실려 과금과 문맥이 함께 늘어난다.
 *
 * <p>구 {@code SlackPhotoIntake.readPhotoInfo}의 자리다. 사라진 것은 <b>버퍼 윈도우로 무엇이 이 턴
 * 것인지 추정하던 부분</b>이고(FR-10 시간 윈도우 — TΔ16에서 소멸), 남은 것은 <i>읽어서 넘긴다</i>뿐이다.
 */
public class TurnPhotoOcr {

    private static final Logger log = LoggerFactory.getLogger(TurnPhotoOcr.class);

    private final PhotoStore photoStore;
    private final PhotoInfoExtractor photoInfoExtractor;

    public TurnPhotoOcr(PhotoStore photoStore, PhotoInfoExtractor photoInfoExtractor) {
        this.photoStore = photoStore;
        this.photoInfoExtractor = photoInfoExtractor;
    }

    /**
     * 사진 이름 목록을 OCR 결과로 바꾼다. 사진이 없거나 못 읽으면 빈 결과 — 컨텍스트 조립기가 걸러낸다(AC-28).
     *
     * <p>POLICY: OCR 실패는 턴을 막지 않는다 — 빈 결과로 진행하고 사진은 첨부로만 남는다
     * (ref: plan.md#ADR-23, AC-Δ5). 실패 수렴은 {@link PhotoInfoExtractor}가 소유한다.
     *
     * @param userId     스테이징 격리 키.
     * @param photoNames 이번 턴에 실린 스테이징 파일명(업로드 응답이 준 값). null·빈 목록이면 호출 없음.
     */
    public VisionExtraction read(String userId, List<String> photoNames) {
        if (photoNames == null || photoNames.isEmpty()) {
            return VisionExtraction.empty();
        }
        Set<String> wanted = Set.copyOf(photoNames);
        // 순서는 저장소가 정한 파일명 오름차순을 따른다 — 커밋 순서와 같아 사진과 아카이브가 어긋나지 않는다.
        List<StagedImage> images = photoStore.readStaged(userId).stream()
                .filter(image -> wanted.contains(image.name()))
                .toList();
        if (images.size() < wanted.size()) {
            // 재시작·고아 청소로 사라졌거나 클라이언트가 없는 이름을 실었다. 있는 것으로 진행한다 —
            // 사진 한 장 때문에 발화를 잃는 것이 더 나쁘다(조용한 유실 금지의 방향이 여기서는 이쪽이다).
            log.warn("턴에 실린 사진 일부가 스테이징에 없다: user={} 요청={} 발견={}",
                    userId, wanted.size(), images.size());
        }
        if (images.isEmpty()) {
            return VisionExtraction.empty();
        }
        // hint는 비운다 — 사진-only 흐름은 커피명을 사진에서 읽고(ADR-23), 폼이 있는 턴에도 draft 값을
        // 힌트로 주면 모델이 사진에서 읽은 것을 그 값 쪽으로 끌어당길 여지가 생긴다(오독 방향의 편향).
        VisionExtraction result = photoInfoExtractor.extract(images, new VisionHint(null, null));
        log.info("수신 사진 OCR 시도: user={} images={} coffeeName={}",
                userId, images.size(), result.coffeeName() != null);
        return result;
    }
}
