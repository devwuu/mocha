-- 모카 초기 스키마 (ref: specs/coffee-note-agent/changes/0028-rdb-storage/delta.md#스키마, ADR-73·74)
--
-- POLICY: FK 제약을 걸지 않는다 (ADR-74, Q-4·Q-12 — 3회 재확인). 참조 무결성과 삭제 전파는
--         애플리케이션(JpaNoteRepository.delete)이 전담한다. FK만 제외이고 UNIQUE·CHECK·NOT NULL은
--         그대로 쓴다(Q-7 확정과 정합). psql로 부모만 지우면 고아 행이 조용히 남는 것을 감수한 결정이다.
-- POLICY: 자식 테이블의 부모 id 인덱스는 각 UNIQUE 제약이 겸한다 — UNIQUE(note_id, seq) 류가 부모 id를
--         선두 컬럼으로 가지므로 별도 인덱스는 같은 구조의 중복이다(delta §인덱스의 "명시적으로 건다"는
--         FK 부재로 자동 생성이 없다는 뜻이며, 중복 인덱스를 만들라는 뜻이 아니다 — 루트 CLAUDE.md §4).
-- POLICY: enum(rating·source·mode·match_type)은 JPA 기본 매핑(@Enumerated STRING = enum 상수명)으로
--         저장한다. Rating의 표시 라벨은 한국어(`맛있다`)라 DB에 넣으면 문구 변경이 데이터 마이그레이션이
--         된다 — 라벨은 표시 계층에만 둔다.

-- ── 노트 계열 ────────────────────────────────────────────────────────────────

-- 노트 = 커피 1종 (ref: data-model.md#2.1, ADR-4)
-- 출처 표시 필드(Sourced<T>)는 (value, source) 두 컬럼으로 떨어진다(ADR-72).
-- 감사 컬럼 4종은 도메인의 created_at/updated_at을 겸한다 — delta §감사 컬럼이 notes·entries에 4종만
-- 규정하므로 같은 의미의 타임스탬프를 두 벌 두지 않는다(Q-5).
CREATE TABLE notes (
    id                     BIGSERIAL PRIMARY KEY,
    -- coffee_name은 노트 정체성이라 값이 반드시 있다(V-9 불변, 검색 앵커).
    coffee_name            TEXT        NOT NULL,
    -- V-5: coffee_name은 검색 보강 대상이 아니다 — source ∈ {user, photo}만 허용.
    coffee_name_source     VARCHAR(16) NOT NULL CHECK (coffee_name_source IN ('USER', 'PHOTO')),
    roastery               TEXT,
    roastery_source        VARCHAR(16) CHECK (roastery_source IN ('USER', 'PHOTO', 'SEARCH')),
    roast_level            TEXT,
    roast_level_source     VARCHAR(16) CHECK (roast_level_source IN ('USER', 'PHOTO', 'SEARCH')),
    -- Q-8: official_notes는 배열 전체에 source 하나 — 값은 note_official_notes, source는 여기.
    official_notes_source  VARCHAR(16) CHECK (official_notes_source IN ('USER', 'PHOTO', 'SEARCH')),
    -- Q-6: 매칭(FR-14) 비교 키. 값은 애플리케이션이 Aliases.normalize()로 계산해 넣는다
    -- (생성 컬럼으로 두면 정규화 로직이 SQL과 Java 두 곳에 생긴다 — REVIEW.md §1·§2).
    coffee_name_normalized TEXT        NOT NULL,
    roastery_normalized    TEXT,
    created_at             TIMESTAMPTZ NOT NULL,
    -- Q-5: _by는 사용자 ID가 아니라 변경 주체(agent/user)다. 사용자 ID 컬럼은 A3에서 별도 추가.
    created_by             VARCHAR(16) NOT NULL,
    modified_at            TIMESTAMPTZ NOT NULL,
    modified_by            VARCHAR(16) NOT NULL
);

-- 원두 구성 (ref: data-model.md#2.1 beans, V-14, ADR-53)
-- 단일 원두도 요소 1개, 정보 전무면 행 0건. description은 V-14 정규화가 비어 있지 않음을 보장한다.
CREATE TABLE note_beans (
    id                 BIGSERIAL PRIMARY KEY,
    note_id            BIGINT      NOT NULL,
    seq                INTEGER     NOT NULL,
    description        TEXT        NOT NULL,
    description_source VARCHAR(16) CHECK (description_source IN ('USER', 'PHOTO', 'SEARCH')),
    process            TEXT,
    process_source     VARCHAR(16) CHECK (process_source IN ('USER', 'PHOTO', 'SEARCH')),
    UNIQUE (note_id, seq)
);

-- 로스터리 전시 테이스팅 노트 (ref: data-model.md#2.1 official_notes, FR-7)
-- 값만 — source는 notes.official_notes_source가 배열 전체에 대해 소유한다(Q-8).
CREATE TABLE note_official_notes (
    id      BIGSERIAL PRIMARY KEY,
    note_id BIGINT  NOT NULL,
    seq     INTEGER NOT NULL,
    value   TEXT    NOT NULL,
    UNIQUE (note_id, seq)
);

-- 내부 매칭·검색 전용 별칭 (ref: data-model.md#2.1 aliases, V-13, ADR-37)
-- alias = 표시 형태(첫 등장 보존), normalized = 대조·중복 제거 기준(Aliases.normalize()).
-- UNIQUE(note_id, kind, normalized)가 V-13의 "정규화 기준 중복 제거"를 제약으로 강제한다.
CREATE TABLE note_aliases (
    id         BIGSERIAL PRIMARY KEY,
    note_id    BIGINT      NOT NULL,
    kind       VARCHAR(16) NOT NULL CHECK (kind IN ('COFFEE_NAME', 'ROASTERY')),
    alias      TEXT        NOT NULL,
    normalized TEXT        NOT NULL,
    UNIQUE (note_id, kind, normalized)
);

-- 검색 참조 링크 (ref: data-model.md#2.1 sources, FR-12 — 동일성 가드 통과 출처만)
CREATE TABLE note_sources (
    id      BIGSERIAL PRIMARY KEY,
    note_id BIGINT  NOT NULL,
    seq     INTEGER NOT NULL,
    url     TEXT    NOT NULL,
    UNIQUE (note_id, seq)
);

-- ── 엔트리 계열 ──────────────────────────────────────────────────────────────

-- 날짜별 시음 기록 = 버전 (ref: data-model.md#2.2, FR-15)
-- V-3: tasted_on을 date 타입으로 둬 형식 위반을 DB가 거른다(Q-1 — 시각은 수집하지 않는다).
-- V-10: UNIQUE(note_id, tasted_on) — 노트 안에서 날짜가 유일 키다(같은 날 여러 번은 brews 회차).
CREATE TABLE entries (
    id          BIGSERIAL PRIMARY KEY,
    note_id     BIGINT      NOT NULL,
    tasted_on   DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  VARCHAR(16) NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    modified_by VARCHAR(16) NOT NULL,
    UNIQUE (note_id, tasted_on)
);

-- 회차 = 한 번 내려서 마신 단위 (ref: data-model.md#2.2 brews, ADR-59, V-15)
-- AC-Δ4: seq가 회차 번호를 명시한다 — 구 "배열 순서 = 회차"의 암묵 순서 의존이 사라진다.
-- recipe/tasting과의 1:1 짝은 recipes·tastings가 brew_id를 PK로 가지는 것으로 표현된다.
CREATE TABLE brews (
    id       BIGSERIAL PRIMARY KEY,
    entry_id BIGINT  NOT NULL,
    seq      INTEGER NOT NULL,
    UNIQUE (entry_id, seq)
);

-- 회차 추출 레시피 (ref: data-model.md#2.2 Recipe, FR-18, V-8)
-- 전 필드 nullable(방식별 분기 없는 flat 스키마). 사용자 발화 전용이라 source 개념이 없다.
-- V-8: 수치는 양수 **유한** — numeric + CHECK(> 0 AND < Infinity). NULL은 CHECK를 통과한다(미언급 필드).
--      `> 0`만으로는 부족하다: Postgres numeric은 PG14부터 Infinity/NaN을 값으로 받고, 비교 규칙상
--      둘 다 0보다 크다고 판정된다(NaN은 모든 수보다 크게 정렬된다). `< 'Infinity'`가 둘을 함께 거른다
--      — 비유한값이 들어오면 렌더 표기·비율 계산이 깨진다(V-8이 유한을 요구하는 이유, changes/0025).
-- brew_id를 PK로 쓰는 것은 1:1 표현이지 FK 제약이 아니다(ADR-74).
CREATE TABLE recipes (
    brew_id  BIGINT PRIMARY KEY,
    method   TEXT,
    dose_g   NUMERIC CHECK (dose_g > 0 AND dose_g < 'Infinity'::NUMERIC),
    water_ml NUMERIC CHECK (water_ml > 0 AND water_ml < 'Infinity'::NUMERIC),
    yield_ml NUMERIC CHECK (yield_ml > 0 AND yield_ml < 'Infinity'::NUMERIC),
    time_sec NUMERIC CHECK (time_sec > 0 AND time_sec < 'Infinity'::NUMERIC),
    temp_c   NUMERIC CHECK (temp_c > 0 AND temp_c < 'Infinity'::NUMERIC),
    grind    TEXT,
    machine  TEXT,
    pouring  TEXT,
    feedback TEXT
);

-- 회차 맛 감상 (ref: data-model.md#2.2 Tasting, V-1·V-11·V-15)
-- V-15: 빈 감상 tasting은 드롭되므로 행이 존재하면 my_taste가 있다 → NOT NULL.
-- V-11: my_taste가 있으면 my_taste_original도 함께 존재한다(누락 시 정규화본을 양쪽에) → NOT NULL.
-- V-1: rating은 4범주 또는 null. Q-7 확정대로 Postgres enum 타입이 아니라 varchar + CHECK.
CREATE TABLE tastings (
    brew_id           BIGINT PRIMARY KEY,
    my_taste          TEXT NOT NULL,
    my_taste_original TEXT NOT NULL,
    rating            VARCHAR(32) CHECK (rating IN ('PERFECT', 'GOOD', 'OKAY_NOT_MINE', 'BAD'))
);

-- ── 확인 대기 ────────────────────────────────────────────────────────────────

-- 확인 대기 노트 (ref: data-model.md#2.3, ADR-3·ADR-73)
-- 사용자당 최대 1건이라 user_id가 PK다(NFR-6 단일 사용자 전제 — 값은 A1에서 단일, 멀티테넌시는 A3).
-- ADR-73 예외: draft는 정규화하지 않고 JSONB로 둔다 — TTL로 소멸하는 임시 상태이고 쿼리 대상이 아니며
-- A2에서 pending 개념 자체가 축소된다. 직렬화는 기존 Jackson 매퍼를 재사용한다.
-- 스키마가 강제하는 무결성은 여기까지고(ADR-66의 mode·created_at·draft 부재 판정), JSONB 내부 결손
-- (draft.coffee_name 공백 등)은 애플리케이션이 계속 판정한다.
CREATE TABLE pending_notes (
    user_id        VARCHAR(64) PRIMARY KEY,
    mode           VARCHAR(16) NOT NULL CHECK (mode IN ('RECORD', 'EDIT')),
    draft          JSONB       NOT NULL,
    -- edit 모드 한정 수정 대상(record 모드는 null). 날짜 이동 시 옛 카드 삭제 근거(AC-39).
    target_note_id BIGINT,
    target_date    DATE,
    -- V-10: draft 날짜 이동이 대상 노트의 기존 엔트리와 충돌하는지 — 미리보기 경고 근거.
    date_conflict  BOOLEAN     NOT NULL,
    -- record 모드 한정 신규/기존 판정(미리보기 표시용, AC-15).
    match_type     VARCHAR(16) CHECK (match_type IN ('NEW', 'EXISTING')),
    match_note_id  BIGINT,
    match_date     DATE,
    -- 미리보기 Slack 메시지 timestamp — 갱신(재전송 아닌 edit) 대상.
    preview_ts     VARCHAR(32),
    -- TTL 판정 기준(mocha.pending.ttl) — 결손 시 만료 계산이 NPE로 새므로 NOT NULL(ADR-66).
    created_at     TIMESTAMPTZ NOT NULL
);

-- ── 인덱스 ───────────────────────────────────────────────────────────────────

-- 매칭(FR-14) 비교 키 인덱스 (Q-6). A1에서는 쓰이지 않는 것이 정상이다 — NoteRepository의 조회
-- 메서드가 findAll()뿐이고 매칭은 여전히 전건 로드 후 메모리 필터다(NoteLookupTools). 활용은 A2에서
-- 목록·필터 UI와 함께 들어온다(delta §동기의 "매칭의 인덱스화"는 A2에 실현된다).
CREATE INDEX idx_notes_normalized ON notes (coffee_name_normalized, roastery_normalized);
CREATE INDEX idx_note_aliases_normalized ON note_aliases (normalized);

-- 날짜 기준 조회(최근 시음·기간 필터) — A2 목록 UI의 정렬·필터 축.
CREATE INDEX idx_entries_tasted_on ON entries (tasted_on);
