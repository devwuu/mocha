package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.image.ImageFormat;
import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.repository.StagedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;

/**
 * 파이프라인 [2.5] — 새 기록 흐름에 사진이 있으면 vision(OCR)으로 커피 정보를 읽어낸다
 * (ref: plan.md §1 [2.5], §3 extract(photos): VisionExtraction; spec FR-19, ADR-23).
 * <p>ADR-15가 예고한 {@link VisionClient} 경계를 재사용한다("커피샵 노트 사진 → 노트 생성"의 실현, NFR-4).
 * <b>묶인 사진 전부를 한 번의 vision 호출</b>에 전달하고(원두 봉투·카페 제공 커피 노트 등 앞/뒷면·여러 장 종합), 장수 상한
 * {@code mocha.vision.max-images}를 넘는 초과분은 호출에 넣지 않는다 — 초과분도 저장(첨부)은 상위
 * 오케스트레이션이 그대로 유지한다(AC-Δ6). 빈 필드 채움·출처 마킹(source=photo)·검색 보강 순서는 상위가 맡고,
 * 여기서는 <b>읽기</b>만 한다.
 * <p>Slack {@code url_private}는 봇 토큰 인증이 필요해 OpenAI가 직접 못 읽으므로, 로컬 스테이징 바이트를
 * {@code data:<mime>;base64,...} URI로 인코딩해 {@link VisionClient#read} 계약({@code imageUrls})에 실어
 * 보낸다(findings-TΔ0 ①, 시그니처 불변).
 * <p>POLICY: 수신 사진 OCR은 1콜 멀티이미지·실패 허용 — 실패·무정보 시 빈 결과, 상위는 첨부로만 진행(흐름 불변)
 * (ref: plan.md#ADR-23, AC-Δ5). {@link VisionClient} 구현이 이미 실패를 {@link VisionExtraction#empty()}로
 * 수렴시키지만, 여기서도 방어적으로 감싸 어떤 예외도 파이프라인으로 새지 않게 한다.
 */
public class PhotoInfoExtractor {

    private static final Logger log = LoggerFactory.getLogger(PhotoInfoExtractor.class);

    private final VisionClient visionClient;
    private final int maxImages;

    /**
     * @param maxImages 1콜당 vision에 전달할 이미지 상한(mocha.vision.max-images). 초과분은 호출 제외(첨부만).
     */
    public PhotoInfoExtractor(VisionClient visionClient, int maxImages) {
        this.visionClient = visionClient;
        this.maxImages = maxImages;
    }

    /**
     * 스테이징된 사진들을 1회 vision 호출로 읽어 커피 정보를 구조화한다.
     *
     * @param images 이번 기록에 묶인 스테이징 사진(파일명 오름차순). 빈 목록이면 호출 없이 빈 결과.
     * @param hint   문맥 힌트 — 커피명은 nullable(사진-only 흐름은 이름을 사진에서 읽는다, ADR-23).
     * @return 읽어낸 값 묶음 — 사진이 없거나 못 읽으면(실패 포함) {@link VisionExtraction#empty()}.
     */
    public VisionExtraction extract(List<StagedImage> images, VisionHint hint) {
        if (images == null || images.isEmpty()) {
            return VisionExtraction.empty();
        }
        // 상한 절삭 — 묶인 사진이 상한을 넘으면 앞에서부터 상한만큼만 1콜에 싣는다(초과분 첨부는 상위 유지, AC-Δ6).
        List<StagedImage> forCall = images.size() > maxImages ? images.subList(0, maxImages) : images;
        if (images.size() > maxImages) {
            log.info("수신 사진 OCR 상한 절삭: 묶임={} 상한={} 호출={}(초과분은 첨부로만)",
                    images.size(), maxImages, forCall.size());
        }
        try {
            // 매직바이트 판별 인코딩은 try 안에서 — 스테이징 게이트(ADR-29)를 뚫고 온 미상 바이트가 있어도
            // 예외가 파이프라인으로 새지 않고 빈 결과로 수렴한다(ADR-23, AC-Δ5).
            List<String> imageUrls = forCall.stream().map(PhotoInfoExtractor::toDataUri).toList();
            return visionClient.read(imageUrls, hint);
        } catch (RuntimeException e) {
            // POLICY: 어떤 실패도 파이프라인으로 새지 않는다 — 첨부로만 진행(흐름 불변, ADR-23, AC-Δ5).
            log.warn("수신 사진 OCR 실패 — 빈 결과로 진행(첨부로만): images={}", forCall.size(), e);
            return VisionExtraction.empty();
        }
    }

    // 로컬 바이트를 OpenAI가 읽을 수 있는 data URI로 인코딩. mime은 확장자가 아니라 매직바이트로 판별한다
    // (ADR-29, V-12 — 확장자·메타 mimetype 불신). 스테이징은 vision 지원 포맷만 통과하므로 여기 도달하는
    // 바이트는 정상 판별된다; 미상이면 mimeType()이 던져 위 catch가 빈 결과로 수렴시킨다.
    private static String toDataUri(StagedImage image) {
        String mime = ImageFormat.detect(image.bytes()).mimeType();
        String base64 = Base64.getEncoder().encodeToString(image.bytes());
        return "data:" + mime + ";base64," + base64;
    }
}
