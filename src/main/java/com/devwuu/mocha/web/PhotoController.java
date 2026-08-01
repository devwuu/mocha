package com.devwuu.mocha.web;

import com.devwuu.mocha.SingleUser;
import com.devwuu.mocha.service.PhotoService;
import com.devwuu.mocha.service.PhotoUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /api/photos} — 사진 업로드 (multipart)
 * (ref: changes/0029 tasks.md TΔ8a, delta.md#D-7·#D-11; spec FR-10/AC-7;
 * 계약 정본 {@code src/test/resources/contract/photo-upload.contract.json}).
 *
 * <p><b>여기서 OCR이 돌지 않는 것이 이 API의 정의다</b>(D-11). 업로드는 사진을 서버에 세우는 것까지고,
 * 응답은 <b>즉시</b> 파일명만 돌려준다 — 그 이름을 클라이언트가 다음 턴 요청의 {@code photos}에 실으면
 * 그때 서버가 읽는다. 사진과 발화를 <i>사용자가 직접 묶는</i> 형태이고(＋는 메시지에 사진을 첨부하는
 * 버튼이다), Slack 시절 버퍼 윈도우(FR-10)가 시간으로 추정하려던 것이 여기서는 추정 없이 성립한다.
 *
 * <p><b>업로드와 턴을 왜 가르는가</b>: 사진은 저장 확정([저장], TΔ8b)까지 서버에 살아 있어야 하므로
 * 턴 요청에 바이트를 실어도 스테이징은 사라지지 않는다. 가르면 얻는 것이 셋이다 — 첨부 즉시 업로드가
 * 시작돼 사용자가 발화를 쓰는 동안 겹쳐 진행되고, 실 LLM 왕복이 실패해도 재전송은 <b>이름뿐</b>이며,
 * 턴 API가 JSON을 유지해 계약({@code agent-turn.contract.json})이 필드 하나 더하는 가산 변경이 된다.
 *
 * <p>컨트롤러가 하는 일은 §2가 정한 대로 <b>파싱·위임·응답 변환</b>이다 — 포맷 게이트·EXIF 제거·스테이징
 * 순서는 {@link PhotoService}가 소유한다.
 */
@RestController
@RequestMapping("/api/photos")
public class PhotoController {

    private static final Logger log = LoggerFactory.getLogger(PhotoController.class);

    private final PhotoService photoService;

    public PhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    /**
     * 사진 묶음을 스테이징하고 파일명을 돌려준다.
     *
     * <p>실패는 상태 코드로 갈린다(TΔ6a와 같은 규약 — 안내 문구의 소유자는 화면이다): 수용하지 않는
     * 포맷이거나 메타데이터를 걷어낼 수 없으면 <b>400이고 한 장도 스테이징되지 않는다</b>
     * ({@link PhotoService#stage} POLICY). 그 밖은 500.
     *
     * @param photos multipart 파트 이름 {@code photos} — 여러 장이면 같은 이름으로 반복한다.
     */
    @PostMapping
    public ResponseEntity<Response> upload(@RequestPart("photos") List<MultipartFile> photos) {
        if (photos == null || photos.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            List<String> names = photoService.stage(SingleUser.ID, toUploads(photos));
            return ResponseEntity.ok(new Response(names.stream().map(Photo::new).toList()));
        } catch (IllegalArgumentException e) {
            // 포맷 게이트(ADR-29)·메타데이터 제거 거부 — 서버 고장이 아니라 받을 수 없는 사진이다.
            log.warn("사진 업로드 거부: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            // POLICY: 실패를 삼키지 않는다 — 파일명은 개인 데이터에 준하므로 남기지 않는다(NFR-7).
            log.warn("사진 업로드 실패: user={} photos={}", SingleUser.ID, photos.size(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // 파트 바이트를 service 값객체로 옮긴다 — MultipartFile(전송 계층 타입)이 service까지 내려가지 않는다.
    private static List<PhotoUpload> toUploads(List<MultipartFile> photos) {
        List<PhotoUpload> uploads = new ArrayList<>(photos.size());
        for (MultipartFile photo : photos) {
            try {
                uploads.add(new PhotoUpload(photo.getOriginalFilename(), photo.getBytes()));
            } catch (IOException e) {
                throw new IllegalStateException("업로드 파트를 읽지 못했다", e);
            }
        }
        return uploads;
    }

    /**
     * 업로드 응답 — 스테이징된 파일명뿐이다.
     *
     * <p>클라이언트가 이 이름을 들고 있다가 전송 시 턴 요청에 싣는다. 경로도 URL도 주지 않는다 —
     * 사진은 외부에서 접근 가능한 주소를 갖지 않고(V-4의 정신), 서버가 아는 것은 스테이징 안의 이름뿐이다.
     */
    public record Response(List<Photo> photos) {
    }

    /** 스테이징된 사진 1장. 지금은 이름뿐이지만 객체로 감싼다 — 배열 원소에 필드를 더할 자리를 남긴다. */
    public record Photo(String name) {
    }
}
