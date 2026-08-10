/**
 * 서버와 공유하는 REST 계약의 TypeScript 판본 (changes/0029 TΔ10).
 *
 * 정본은 `src/test/resources/contract/`의 JSON 파일이고 — `turn-draft.contract.json`(TΔ2 캡처본),
 * `agent-turn.contract.json`, `note-commit.contract.json`, `note-candidates.contract.json`(TΔ11),
 * `agent-cancel.contract.json`(TΔ6b), `note-list.contract.json`(TΔ12) — 자바 쪽에서
 * `ClientApiContractTest`가 서로의 정합을 단언한다. 이 파일은 그 형태를 타입으로 옮긴 것이다.
 *
 * 계약을 바꾸려면 JSON 파일과 이 파일을 함께 고친다. 필드명이 어긋나면 컴파일러가 아니라
 * **런타임에 값이 조용히 사라지는** 방식으로 실패한다 — 사용자가 폼에서 고친 값이 되돌아가는
 * 이 델타의 실패(delta.md §1.2)가 그대로 재현되는 경로다.
 */

/** 출처 우선순위 user > photo > search (V-6). 사용자가 폼에서 고치면 그 필드는 `user`가 된다. */
export type Source = 'user' | 'photo' | 'search'

/** 출처 표시 필드 — `{ value, source }` (V-5). */
export interface Sourced<T> {
  value: T
  source: Source
}

/** 4범주 평가 또는 null(미언급) — 정의 외 값은 서버가 거부한다(V-1). */
export const RATINGS = ['완전 내스타일', '맛있다', '맛은 있는데 내스타일은 아님', '맛이 없다'] as const
export type Rating = (typeof RATINGS)[number]

/** 원두 1종 — 단일 원두도 요소 1개, 정보 전무면 빈 배열(V-14). */
export interface Bean {
  description: Sourced<string>
  process: Sourced<string> | null
}

/**
 * 회차 추출 레시피 — 방식별 분기 없는 flat 스키마, 전 필드 nullable(V-8). 사용자 발화 전용(보강 금지).
 *
 * **changes/0030 재편**(ADR-86): 수치 6종(`dose_g`·`water_ml`·`yield_ml`·`time_sec`·`temp_c`·**`grind`**) +
 * 텍스트 4종(`method`·`grinder`·`detail`·`feedback`)이다. 구 `grind`는 `"210클릭 (매버릭 2.0)"` 단일
 * 문자열이었는데 **수치 `grind` + 이름 `grinder` 두 칸으로 갈렸고**, 구 `machine`은 폐기, 구 `pouring`은
 * `detail`이 됐다. 숫자로 떨어지지 않는 분쇄 표현·기구 정보의 행선지는 `detail` 하나다.
 */
export interface Recipe {
  method: string | null
  dose_g: number | null
  water_ml: number | null
  yield_ml: number | null
  time_sec: number | null
  temp_c: number | null
  grind: number | null
  grinder: string | null
  detail: string | null
  feedback: string | null
}

/** 회차 맛 감상 — `my_taste`가 있으면 `my_taste_original`도 함께 있다(V-11). */
export interface Review {
  my_taste: string | null
  my_taste_original: string | null
  rating: Rating | null
}

/** 회차 1개 — 배열 순서가 곧 회차 번호다. 둘 다 null인 회차는 서버가 드롭한다(V-15). */
export interface Cup {
  recipe: Recipe | null
  review: Review | null
}

/** 날짜별 시음 기록 — `date`가 tasting_days 내 유일 키다(V-3). */
export interface TastingDay {
  date: string
  cups: Cup[]
  updated_at: string | null
}

/**
 * 작성 중인 노트.
 *
 * `aliases`가 없는 것이 이 델타의 결정이다(TΔ10, TΔ2 이월 (b) 해소): 내부 매칭 전용 별칭(V-13)은
 * 폼이 표시하지도 편집하지도 않고, 커밋 시 축적은 저장된 값을 읽어 서버가 계산한다. 계약에서 뺀다.
 */
export interface DraftNote {
  id: number | null
  coffee_name: Sourced<string> | null
  roastery: Sourced<string> | null
  beans: Bean[]
  roast_level: Sourced<string> | null
  official_notes: Sourced<string[]> | null
  sources: string[]
  tasting_days: TastingDay[]
  created_at: string | null
  updated_at: string | null
}

/**
 * 매칭 배지 — 이 폼을 저장하면 무슨 일이 일어나는가의 선언. 사용자가 배지에서 바꿀 수 있으므로 draft의
 * 일부다(OQ-2 ㉢, 시트는 TΔ11).
 *
 * **`edit`이 TΔ28a에서 는다**(D-14) — `existing`과 갈리는 것은 «대상»이 아니라 «의도»다:
 * 전자는 *"같은 커피를 또 마셨다"*(회차가 는다), 후자는 *"그때 그 기록이 틀렸다"*(있던 회차가 바뀐다).
 * 같은 노트를 가리켜도 결과가 반대라 축을 합칠 수 없고, 저장 경로도 갈린다(POST ↔ PATCH, TΔ28b).
 *
 * **`edit`의 `date`만 필수다.** `existing`의 것은 미리보기 표기용이라 없어도 되지만(어느 날에 붙일지는
 * 서버가 정한다), `edit`은 **그 날짜가 곧 대상**이라 없으면 무엇을 고칠지가 정해지지 않는다 —
 * 시스템이 추측으로 채우는 자리를 만들지 않는 것이 이 필수 표기의 뜻이다(사용자 확정 2026-08-02).
 *
 * **`edit.date`는 «대상»이지 «폼이 든 날짜»가 아니다**(TΔ28c). 수정 모드에서 날짜는 편집 가능하고, 폼의
 * `tasting_days[0].date`가 이 값과 달라지면 그것이 곧 날짜 이동 요청이다 — 저장이 `PATCH
 * /api/notes/{id}/tasting-days/{date}`로 나갈 때 **경로에 실리는 것이 이쪽**이고 본문에 실리는 것이 폼의
 * 날짜다(`TastingDayUpdate`의 같은 축). 그래서 이 필드는 폼 편집으로 움직이지 않는다 — 함께 움직이면
 * *"어느 시음일을 고치는 중인가"*를 잃는다.
 */
export type MatchInfo =
  | { type: 'new' }
  | { type: 'existing'; note_id: number; date?: string }
  | { type: 'edit'; note_id: number; date: string }

/**
 * 매칭 후보 1건 — 변경 시트의 한 줄 (changes/0029 TΔ11).
 *
 * **로스터리가 필드에 들어간 이유가 도메인이다**: 싱글 오리진은 커피명이 산지·농장·품종에서 오므로
 * 로스터리가 다르면 같은 이름의 다른 커피인 것이 정상이다. 커피명만 보여주면 동명 후보를 구분할 축이
 * 없다(사용자 확정 2026-08-01).
 *
 * 둘 다 nullable이다 — 로스터리를 모르는 노트가 있을 수 있고, `latest_date`는 시음일이 없는 노트에서 빈다.
 */
export interface NoteCandidate {
  note_id: number
  coffee_name: string
  roastery: string | null
  latest_date: string | null
}

/**
 * `GET /api/notes/candidates?q=` — 정렬은 서버가 소유한다: **커피명 → 로스터리 → 최근 시음일 내림차순**
 * (사용자 확정 2026-08-01, TΔ7). `q`가 비면 전체를 같은 순서로 돌려준다 — 커피명이 아직 안 뽑힌
 * 상태에서도 시트가 쓸모 있어야 한다.
 *
 * **관련도 순위는 의도적으로 없다**: 스키마에 그것을 표현할 수단이 없어(임베딩·`pg_trgm` 부재) SQL로
 * 흉내 내면 이름만 관련도인 코드가 남는다. 필요해지면 단계를 늘리지 말고 임베딩으로 간다.
 *
 * 대조는 서버가 커피명·로스터리·**별칭**을 정규화 기준으로 본다 — `예가체프 지1`로 쳐도 `예가체프 G1`
 * 노트가 잡힌다. 그래서 **클라이언트가 결과를 한 번 더 거르면 안 된다**(거른 축에 별칭이 없다).
 */
export interface NoteCandidatesResponse {
  candidates: NoteCandidate[]
}

/** 폼 상태 전체 = 다음 턴의 draft = 저장 본문. 방향만 다른 같은 값이다. */
export interface Draft {
  note: DraftNote
  match: MatchInfo
}

/**
 * `POST /api/agent/turn` — 첫 턴은 폼이 없으므로 `draft`가 null이다.
 *
 * `photos`는 이번 메시지에 첨부된 사진의 **스테이징 파일명**이다(TΔ8a, D-11) — `POST /api/photos` 응답이
 * 준 값을 그대로 싣는다. 바이트가 아니라 이름인 것이 계약의 값이다: 턴이 JSON을 유지하고, 실패한 턴을
 * 재시도할 때 사진을 다시 태우지 않는다.
 *
 * **클라이언트는 사진을 병합하지 않는다.** OCR·검색 보강·필드 채움은 전부 서버 턴 안에서 모델이 한다 —
 * 출처 우선순위(user > photo > search)가 프롬프트와 TS 코드로 이중화되지 않게 하려는 것이 D-11의 결정이다.
 */
export interface AgentTurnRequest {
  utterance: string
  draft: Draft | null
  photos: string[]
}

/** 턴 응답. 제안이 없었던 턴(잡담·조회·검증 거부)은 `draft`가 null이고, 그때 폼은 그대로 남는다. */
export interface AgentTurnResponse {
  reply: string
  draft: Draft | null
}

/** 스테이징된 사진 1장 — 서버가 아는 것은 이름뿐이다(경로도 URL도 주지 않는다). */
export interface UploadedPhoto {
  name: string
}

/**
 * `POST /api/photos` (multipart, 파트 이름 `photos`) — 업로드 → EXIF 제거 → 포맷 게이트 → 스테이징
 * (TΔ8a, D-7·D-11).
 *
 * **여기서 OCR이 돌지 않는 것이 이 API의 정의다.** 응답은 즉시 파일명만 돌아오고, 사진을 읽는 것은
 * 그 이름이 실린 다음 턴이다. 그래서 ＋는 *메시지에 사진을 첨부하는* 버튼이지 독립 동작이 아니다.
 *
 * 수용 포맷은 **JPEG/PNG뿐**이고 판별은 매직바이트다(확장자·Content-Type 불신, ADR-29). 한 장이라도
 * 거부되면 **400이고 아무것도 스테이징되지 않는다** — 부분 성공을 표현할 자리가 계약에 없다.
 */
export interface PhotoUploadResponse {
  photos: UploadedPhoto[]
}

/**
 * 갤러리 그리드의 한 칸 — 노트 1건의 납작한 사영 (changes/0029 TΔ12).
 *
 * 필드가 `NoteCandidate` + `thumbnail_url`인 것은 우연이 아니다: 두 화면 다 노트를 *고르는* 자리라
 * 3단 중첩(tasting_days → cups → recipe/review)을 한 줄도 쓰지 않는다. 상세가 무엇을 보여줄지는
 * `GET /api/notes/{id}`가 따로 답한다(TΔ13a·TΔ5a).
 *
 * `thumbnail_url`은 **서버가 만든 완성된 경로**다 — 클라이언트는 `<img src>`에 그대로 꽂고 URL 규칙을
 * 알지 않는다. 아카이브 상대 경로(`photos/…`, V-4)를 프론트가 조립하면 같은 규칙이 양쪽에 이중화되고,
 * 폴더 접미가 생성 시점 스냅샷이라(ADR-75) 클라이언트가 재계산할 수 있는 값도 아니다.
 *
 * `roastery`·`latest_date`·`thumbnail_url`이 모두 nullable이다 — 로스터리를 모르는 노트, 시음일이
 * 없는 노트(삭제 직후), 사진 없이 발화만으로 기록한 노트가 전부 정상 상태다.
 */
export interface NoteSummary {
  note_id: number
  coffee_name: string
  roastery: string | null
  latest_date: string | null
  thumbnail_url: string | null
}

/**
 * 필터 칩이 보여줄 선택지 — **저장된 값에서 나온다**(사용자 확정 2026-08-01).
 *
 * 목록 응답에 동봉하는 이유는 무한 스크롤이라 클라이언트가 한 페이지에서 전체 값을 알 수 없어서다.
 * 별도 엔드포인트로 가르면 화면 진입에 요청이 둘이 되고, 그 둘이 어긋나는 순간 **있지도 않은 로스터리를
 * 고를 수 있는** 상태가 생긴다.
 *
 * **평가는 여기 없다** — 4범주 고정(V-1)이라 클라이언트 상수가 소유한다(`RATINGS`). 원산지도 없다:
 * 자유 텍스트 부분일치라 열거할 값 집합이 애초에 없다(아래 `NoteQuery.origin` 주석).
 */
export interface NoteFacets {
  roastery: string[]
  process: string[]
}

/**
 * 갤러리 필터 상태 = `GET /api/notes`의 쿼리 파라미터 (changes/0029 TΔ12, AC-4).
 *
 * **같은 축 안은 OR, 축 간은 AND**다(사용자 확정 2026-08-01) — *"프릳츠 아니면 모모스의 워시드"*가
 * 표현된다. 다중 축은 같은 키를 반복해 싣는다(`?roastery=프릳츠&roastery=모모스`).
 *
 * `origin`만 단일 자유 텍스트인 것은 **데이터 모델에 원산지 컬럼이 없기 때문**이다(사용자 확정
 * 2026-08-01). ADR-53(changes/0021)이 구 `origin`/`process` 필드를 `beans[]`로 대체하며 원산지는
 * `note_bean.description`의 자유 텍스트(*"에티오피아 예가체프 헤어룸"*)에 녹았다. 그래서 이 축만
 * 열거 대신 부분일치로 근사한다 — 정확도가 표기에 의존하는 대가를 지고, 실사용에서 값이 쌓이는 것을
 * 본 뒤에 구조화를 판단한다(루트 §4 right-sizing).
 *
 * **날짜 범위 축은 없다** — 필요가 관측되면 확장한다(`open-questions.md` 검색 절).
 */
export interface NoteQuery {
  q: string
  roastery: string[]
  process: string[]
  origin: string
  rating: Rating[]
}

/**
 * `GET /api/notes` 응답 — 커서 기반 무한 스크롤 (사용자 확정 2026-08-01).
 *
 * `next_cursor`는 **불투명 문자열**이다. 클라이언트가 만들지도 해석하지도 않고 받은 값을 그대로 되싣는다
 * — 정렬 축(최근 시음일 내림차순 → note_id 내림차순)이 바뀌어도 클라이언트가 따라 바뀌지 않게 하려는
 * 것이다. 마지막 페이지면 null이고, 그것이 "더 없다"의 유일한 신호다.
 *
 * `total`은 **필터가 적용된 총 건수**다 — 헤더의 *"N편의 기록"*이 그 값이고, 필터를 걸면 함께 줄어든다.
 * 페이지마다 같은 값이 실려 온다.
 */
export interface NoteListResponse {
  notes: NoteSummary[]
  next_cursor: string | null
  total: number
  facets: NoteFacets
}

/**
 * 저장된 노트의 사진 1장 — 상세 화면이 보여줄 완성 URL (changes/0029 TΔ13a).
 *
 * 목록의 `thumbnail_url`과 **같은 접두**(`/api/photos/`)를 쓴다. 같은 자원의 두 표면이므로 접두가 갈리면
 * 갤러리에서 보던 사진과 상세에서 보는 사진이 다른 규칙으로 만들어진다. 업로드 응답(`UploadedPhoto`)이
 * 이름만 주는 것과 대칭이다 — 저장 전에는 이름, 저장 후에는 URL이고, 그 사이를 잇는 것이 서버다.
 */
export interface NotePhoto {
  url: string
}

/**
 * 저장된 회차의 감상 — **원문(`my_taste_original`)이 없다**.
 *
 * V-11이 원문을 함께 저장하게 하지만 *"렌더는 `my_taste`만 사용"*이 같은 규칙의 뒷문장이고, 상세도 렌더다.
 * 폼의 `Review`와 타입을 나눈 것이 그 차이를 컴파일러가 지키게 한다(TΔ10의 `aliases` 절단과 같은 판단 —
 * 화면이 쓰지 않는 값은 계약에서 뺀다).
 */
export interface NoteDetailReview {
  my_taste: string | null
  rating: Rating | null
}

/** 저장된 회차 1개 — 레시피·감상 중 최소 하나는 non-null이다(V-15). */
export interface NoteDetailCup {
  recipe: Recipe | null
  review: NoteDetailReview | null
}

/**
 * 저장된 날짜별 시음 기록 + **그 날의 사진**.
 *
 * 사진이 노트가 아니라 시음일에 붙는 것은 `note_photo`의 참조 축이 `(note_id, tasted_on)`이기 때문이다
 * (TΔ8b, 사용자 확정 2026-08-01). 화면은 가장 최근 날짜의 첫 장을 상단 히어로로 쓰고 나머지를 그 날짜
 * 섹션에 두는데, **계약 하나로 둘 다 된다** — 노트 레벨 배열을 따로 두면 같은 사진이 두 자리에 실린다.
 */
export interface NoteDetailTastingDay {
  date: string
  cups: NoteDetailCup[]
  photos: NotePhoto[]
}

/**
 * `GET /api/notes/{id}` — 노트 전문 (changes/0029 TΔ13a, 구현은 TΔ5a). 없는 id면 **404**다.
 *
 * **`coffee_name`이 non-null인 것이 draft와 갈리는 지점**이다. 작성 중인 노트는 커피명이 아직 안 뽑혔을
 * 수 있지만 저장된 노트는 그것이 정체성이라 반드시 있다(V-9 불변, `note.coffee_name NOT NULL`).
 *
 * **출처를 그대로 싣는다**(사용자 확정 2026-08-01). 두 가지가 걸려 있다: ① 상세가 캡처 폼과 같은 어휘로
 * *"이 값은 사진에서 읽은 것"*을 보여준다 ② **TΔ13b 수정 폼이 이 응답을 그대로 딛는다** — 출처를 버리면
 * 로스터리만 고쳐도 공식 노트의 출처가 `user`로 덮여, 이후 검색 보강이 덮지 못하는 값이 조용히 늘어난다
 * (V-6이 막으려던 방향의 반대편 사고).
 *
 * 싣지 않는 것: `aliases`(V-13 내부 전용) · `created_at`/`updated_at`(화면이 쓰지 않는다) ·
 * `my_taste_original`(위 참조). 노트 레벨 `id`는 `note_id`로 이름을 맞춘다 — 목록·후보와 같은 어휘다.
 */
export interface NoteDetail {
  note_id: number
  coffee_name: Sourced<string>
  roastery: Sourced<string> | null
  beans: Bean[]
  roast_level: Sourced<string> | null
  official_notes: Sourced<string[]> | null
  sources: string[]
  tasting_days: NoteDetailTastingDay[]
}

/**
 * `PATCH /api/notes/{id}` — 저장된 노트의 **사실** 수정 (changes/0029 TΔ13b, 구현은 TΔ5b-3, AC-5).
 *
 * **`coffee_name`이 없는 것이 이 계약의 핵심이다.** 커피명은 노트 생성 후 불변이고(V-9) 이름이 다르면
 * 다른 커피다 — 필드를 두지 않아 **구조로 차단**한다. 구 `propose_edit` patch 스키마가 같은 방식으로
 * 막던 자리이고(data-model §3.4), 그 tool이 TΔ1에서 사라지며 잠시 비었다가 여기서 다시 선다.
 * `NoteTxService.updateMeta`의 저장값 대조는 그 아래의 최종 방어선으로 남는다(TΔ4a).
 *
 * 상세 응답(`NoteDetail`)에서 `coffee_name`·`note_id`·`tasting_days`를 뺀 것과 정확히 같다 — 수정 폼이
 * `GET /api/notes/{id}`를 그대로 딛기 때문이고, **출처를 함께 싣는 이유가 그것이다**: 고치지 않은 필드는
 * 원래 출처를 유지해야 이후 검색 보강이 닿을 수 있는 값으로 남는다(V-6이 막으려던 방향의 반대편 사고).
 */
export interface NoteMetaUpdate {
  roastery: Sourced<string> | null
  beans: Bean[]
  roast_level: Sourced<string> | null
  official_notes: Sourced<string[]> | null
  sources: string[]
}

/**
 * `PATCH /api/notes/{id}/tasting-days/{date}` — 그 날짜 시음 기록의 회차 교체 + 날짜 이동
 * (changes/0029 TΔ13b, 구현은 TΔ5b-3, AC-5).
 *
 * 경로의 `{date}`가 **대상**이고 본문의 `date`가 **결과**다. 둘이 다르면 날짜 이동이고, 같으면 제자리
 * 수정이다. 한 필드가 두 뜻을 지지 않게 자리를 나눈 것이라(`NoteTxService.replaceTastingDay(noteId,
 * targetDate, tastingDay)`가 이미 그 모양이다) 요청만 보고 어느 쪽인지 알 수 있다.
 *
 * **이동처에 이미 기록이 있으면 그날의 회차 뒤로 합쳐진다**(D-12, 2026-08-01 사용자 확정). 시음일 총수는
 * 1 줄고(둘이 하나가 된다) 그날의 사진도 함께 옮겨 온다. 구 V-10은 이동처를 *덮어썼는데* — 시음일이
 * 하루치 감상 1건이던 시절(changes/0012)의 규칙이라 회차 배열(ADR-59) 위에서는 그날의 N회차를 통째로
 * 지우는 뜻이 됐다. 캡처 경로가 같은 상황에 이미 *회차 append*로 답하고 있다(ADR-4·59).
 *
 * `review`에 **`my_taste_original`이 없다** — 폼이 고치는 것은 정규화본이고, 원문 필드가 비면 서버가
 * 정규화본을 양쪽에 담는다(V-11 뒷문장). 수정하면 *"말한 그대로"*가 편집본으로 수렴하는 것이 이 계약이
 * 받아들인 대가다(사용자 확정 2026-08-01).
 *
 * **회차를 더하거나 지우지 않는다** — 배열 길이·순서가 요청과 응답에서 같다. 새 회차는 대화로 쌓는 것이
 * 캡처 경로이고(ADR-4·59), 회차 삭제는 spec에 없는 동작이다(루트 §3).
 */
export interface TastingDayUpdate {
  date: string
  cups: NoteDetailCup[]
}

/** `POST /api/notes` — 폼 확정 저장(= 구 [저장] 버튼). 본문이 곧 확정된 draft다. */
export type NoteCommitRequest = Draft

/**
 * 커밋 응답은 식별자뿐이다(TΔ6b 확정).
 *
 * 저장된 노트 전체를 싣지 않는다 — 기존 노트에 병합하면 폼의 메타 수정(로스터리·원두·로스팅·공식 노트)은
 * ADR-4에 따라 저장되지 않는데, 그 사실을 알리는 자리는 응답 본문이 아니라 **저장 완료 문구**다
 * (`ChatScreen`이 신규/병합으로 갈라 쓴다). 사실을 고치는 경로는 상세 수정 화면(TΔ13)이다.
 */
export interface NoteCommitResponse {
  note_id: number
}

/**
 * `POST /api/agent/cancel` — 작성 중이던 노트를 버렸다는 통지 (TΔ6b).
 *
 * 요청·응답 모두 **본문이 없다**(204). 무엇을 취소하는지는 서버가 안다 — 사용자당 트랜스크립트가 1건이고,
 * 서버에는 취소할 draft가 애초에 없다(pending 소멸, TΔ4). 정본은
 * `src/test/resources/contract/agent-cancel.contract.json`이라 타입으로 옮길 것이 없고, 이 주석이
 * 계약 파일과 짝이다.
 */

/**
 * `GET /api/notes/{id}/tasting-days/{date}/card?type=&n=` — 회차 카드 JPG 온디맨드 (TΔ9, OQ-3 ㉡).
 *
 * 응답이 이미지 바이트라 타입으로 옮길 본문이 없다 — 계약의 정본은
 * `src/test/resources/contract/note-card.contract.json`이고 이 주석이 그 짝이다. 여기 사는 것은
 * **요청의 유일한 열거 값**뿐이다.
 *
 * **URL은 클라이언트가 조립한다** — 사진(`NotePhoto.url`)과 갈리는 지점이다. 사진 경로는 폴더 접미가
 * 생성 시점 스냅샷이라(ADR-75) 클라이언트가 재계산할 수 없는 서버 소유 규칙이지만, 카드 주소는
 * 화면이 이미 들고 있는 값(`note_id` · `date` · 회차 순번)만으로 정해진다. 계약에 URL을 실으면 서버가
 * **아직 굽지도 않은 카드**의 주소를 상세 응답마다 회차 수만큼 늘어놓게 된다.
 */
export type CardType = 'review' | 'recipe'
