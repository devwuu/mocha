package com.devwuu.mocha.render;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 파이프라인 [6] — 회차 카드 산출 경계 (ref: plan.md §1 [6], §3; ADR-1, ADR-7, ADR-10, ADR-54·59).
 * <p>저장소(원본)만으로 회차 카드 JPG({@code cards/<접미>/<date>-taste-<n>.jpg}·{@code <date>-recipe-<n>.jpg},
 * n = 회차)를 재생성한다. 산출물은 파생물이며 삭제해도 데이터 손실이 없어야 한다(NFR-3, AC-6).
 * 카드 HTML은 굽는 순간의 중간 입력이라 파일로 남기지 않는다(ADR-10). 갤러리 페이지는 폐기됐다(ADR-9).
 * <p>POLICY: 렌더러는 저장소 외 어떤 상태도 읽지 않는다 — 전체 리렌더 가능성 보장 (ref: plan.md#ADR-1, AC-6).
 * <p><b>대상 지정은 노트 id다</b>(changes/0028 ADR-75, TΔ6c). 카드 폴더 접미는 호출부가 아니라 렌더러가
 * 노트에서 만든다 — 렌더러는 어차피 노트를 조회하므로, 접미를 인자로 받으면 조립 규칙이 호출부마다 갈린다.
 *
 * <p><b>카드는 온디맨드다</b>(changes/0029 TΔ9, OQ-3 ㉡ 확정). 저장이 카드를 굽던 시절은 끝났고 —
 * 그 배달처였던 Slack이 사라졌다(TΔ16) — 이제 카드를 요청하는 것은 상세 화면의 [공유] 하나다. 그래서
 * {@code artifact/cards/}는 <b>완결된 산출 집합이 아니라 캐시</b>이고, 이 인터페이스의 두 메서드가
 * 그 캐시의 양쪽 끝이다:
 * <ul>
 *   <li>{@link #tastingDayCard} — <b>읽기가 채운다</b>. 있으면 그대로, 없으면 그때 굽는다.</li>
 *   <li>{@link #renderAll} — <b>전체 예열 + 고아 정리</b>. {@code --rerender}가 쓰고, DB만으로 파생물
 *       전체가 복원됨을 보이는 자리다(NFR-3).</li>
 * </ul>
 * <b>캐시를 비우는 쪽은 여기 없다</b> — 노트를 고치면 그 카드가 낡는데, 그 판정은 쓰기 경로가 지고
 * ({@code NoteService}) 렌더러는 쓰기를 모른다. 반대 방향 주입은 순환이기도 하다(렌더러가 조회원으로
 * {@code NoteService}를 잡는다).
 *
 * <p>구현: {@link ThymeleafNoteRenderer}.
 */
public interface NoteRenderer {

    /** 모든 시음일의 회차 카드 JPG 전체 리렌더 + 고아 정리(AC-Δ7). {@code --rerender}가 쓴다. */
    void renderAll();

    /**
     * 카드 1장을 돌려준다 — <b>캐시에 있으면 그대로, 없으면 그때 굽는다</b>(TΔ9, OQ-3 ㉡).
     *
     * <p><b>미스는 시음일 단위로 채운다</b>: 요청은 카드 1장이지만 굽는 것은 그 시음일의 회차 카드
     * 전부다({@link #renderTastingDayCard}). 근거는 호출 패턴이다 — 공유는 그 회차의 감상·레시피를 함께
     * 집으므로 두 번째 요청이 곧 이어지고, 브라우저 기동이 카드 장수보다 비싸다. 덤으로 회차 감소·파트
     * 소멸로 남은 옛 번호 카드가 그 정리 경로에서 함께 걷힌다.
     *
     * @param noteId     대상 노트 id.
     * @param date       대상 시음일 날짜.
     * @param type       카드 종류.
     * @param cupNumber 회차 번호(1부터 — 배열 순서가 곧 번호다, ADR-59).
     * @return 카드 JPG 경로. 노트·시음일·회차가 없거나 그 회차에 해당 파트가 없으면 빈 Optional
     *         (AC-78 — 없는 파트는 카드가 없는 것이 정상이다).
     */
    Optional<Path> tastingDayCard(long noteId, LocalDate date, CardType type, int cupNumber);

    /**
     * 대상 시음일의 회차 카드 전부(review 있는 회차의 감상 카드 + recipe 있는 회차의 레시피 카드 —
     * AC-78)를 굽고 경로 목록을 반환한다(회차 오름차순, 회차 안에서는 감상 → 레시피). 재생성 전
     * 그 시음일의 옛 카드 파일을 정리해 회차 감소·파트 소멸 재저장의 잔존 카드를 없앤다(changes/0021 TΔ5a).
     *
     * <p><b>부르는 것은 {@link #tastingDayCard}의 캐시 미스뿐이다</b>(TΔ9) — 구 호출부인 저장 커밋 직후
     * 증분 렌더는 온디맨드 전환으로 사라졌다.
     *
     * @param noteId 대상 노트 id.
     * @param date   대상 시음일 날짜.
     * @return 구워진 회차 카드 JPG 경로 목록.
     */
    List<Path> renderTastingDayCard(long noteId, LocalDate date);
}
