package com.devwuu.mocha.web;

import com.devwuu.mocha.render.CardType;
import com.devwuu.mocha.render.NoteRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

/**
 * {@code GET /api/notes/{id}/entries/{date}/card?type=taste|recipe&n=} — 회차 카드 온디맨드
 * (ref: changes/0029 tasks.md TΔ9, open-questions.md OQ-3 ㉡·OQ-10; spec FR-16 개정 대상;
 * 계약 정본 {@code src/test/resources/contract/note-card.contract.json}).
 *
 * <p><b>이 엔드포인트가 카드의 유일한 소비자다.</b> 카드를 만들던 이유는 공유였고(FR-7) 배달처는
 * Slack이었는데(FR-16) 그 배달처가 사라졌다(TΔ16). 그래서 굽는 시점이 <i>저장</i>에서 <i>공유 버튼</i>으로
 * 옮겨졌고, 그 결과 저장 경로가 짧아졌다 — 아무도 보지 않을 이미지를 위해 브라우저를 띄우지 않는다.
 *
 * <p><b>사진과 달리 컨트롤러가 진다.</b> 사진은 리소스 핸들러가 돌려준다({@code WebConfig}) — 그 서빙에
 * 우리 로직이 한 줄도 없기 때문이다. 카드에는 있다: <i>없으면 굽는다</i>. 그 판단이 요청 처리의 본체라
 * 정적 매핑 뒤에 숨길 수 없다.
 *
 * <p><b>{@code NoteController}와 갈라 둔다</b>: 경로는 노트 아래지만 다루는 것은 노트가 아니라 <b>파생
 * 이미지</b>이고, 잡는 협력자도 {@code NoteService}가 아니라 {@link NoteRenderer} 하나다. 합치면 노트
 * 표면이 서로 다른 매체 둘(JSON·JPEG)을 함께 지게 된다.
 *
 * <p>컨트롤러가 하는 일은 §2가 정한 대로 <b>파싱·위임·응답 변환</b>이다 — 캐시 히트/미스 판정도 산출도
 * {@link NoteRenderer#tastingDayCard}가 소유하고, 무효화는 쓰기 경로({@code NoteService})가 소유한다.
 */
@RestController
@RequestMapping("/api/notes")
public class CardController {

    private static final Logger log = LoggerFactory.getLogger(CardController.class);

    private final NoteRenderer noteRenderer;

    public CardController(NoteRenderer noteRenderer) {
        this.noteRenderer = noteRenderer;
    }

    /**
     * 회차 카드 JPG 1장. 캐시에 있으면 그대로, 없으면 그때 굽는다(첫 요청은 헤드리스 브라우저 기동을
     * 지므로 초 단위로 느리다 — 화면이 그동안 버튼을 잠근다).
     *
     * <p>실패는 상태 코드로 갈린다: 없는 노트·시음일·회차, 그리고 <b>그 회차에 없는 파트</b>는 전부
     * <b>404</b>다(AC-78 — 레시피 없는 회차의 레시피 카드는 오류가 아니라 없는 자원이다). {@code type}
     * 표기가 4범주 밖이면 변환이 거부해 <b>400</b>, 굽다 실패하면 <b>500</b>이다.
     *
     * <p>POLICY: 카드는 캐시하지 않는다({@code no-store}) — 사진과 갈리는 지점이다. 사진은 불변이라
     * 30일 캐시가 붙지만({@code WebConfig}) 카드는 <b>노트를 고치면 내용이 바뀌는 파생물</b>이고, 서버가
     * 캐시를 걷어도 브라우저가 들고 있으면 사용자는 옛 카드를 공유한다. 굽는 값이 이미 서버 캐시에
     * 있으므로 왕복 비용도 파일 읽기 한 번이다.
     *
     * @param n 회차 번호(1부터). 기본값이 1인 것은 회차가 하나뿐인 기록이 대다수여서다.
     */
    @GetMapping("/{id:\\d+}/entries/{date}/card")
    public ResponseEntity<Resource> card(
            @PathVariable long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "type") CardType type,
            @RequestParam(name = "n", defaultValue = "1") int n) {
        Optional<Path> card;
        try {
            card = noteRenderer.tastingDayCard(id, date, type, n);
        } catch (RuntimeException e) {
            // POLICY: 굽기 실패를 삼키지 않는다 — 저장된 기록은 멀쩡하고 실패한 것은 파생물뿐이라
            //         화면이 "카드를 만들지 못했어요"로 수렴시킨다 (ref: plan.md §7).
            log.warn("카드 생성 실패: noteId={} date={} type={} n={}", id, date, type.id(), n, e);
            return ResponseEntity.internalServerError().build();
        }
        if (card.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return serve(card.get());
    }

    /**
     * 파일 바이트를 그대로 흘린다. 파일명을 실어 보내는 것은 <b>내려받기 경로의 이름</b>이 되기 때문이다 —
     * 공유 시트가 열리지 않는 환경에서 화면이 이 응답을 그대로 저장한다({@code detail/share.ts} 폴백).
     */
    private ResponseEntity<Resource> serve(Path card) {
        long length;
        try {
            length = Files.size(card);
        } catch (IOException e) {
            // 방금 존재를 확인한 파일이라 여기 오면 파일시스템 쪽 사고다 — 조용히 0바이트를 흘리지 않는다.
            throw new UncheckedIOException("카드 파일을 읽지 못했다: " + card, e);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(card.getFileName().toString()).build().toString())
                .body(new FileSystemResource(card));
    }
}
