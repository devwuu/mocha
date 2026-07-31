package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.repository.entity.PendingNoteEntity;
import com.devwuu.mocha.repository.jpa.PendingNoteEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * Postgres 기반 PendingStore — {@code pending_note} 한 행
 * (ref: changes/0028-rdb-storage/delta.md#ADR-73, tasks.md TΔ8).
 *
 * <p>구 {@code JsonFilePendingStore}를 대체한다. 파일 시절의 원자적 쓰기(임시파일 → move)는 근거가
 * 소멸했다 — 한 행의 갱신은 그 자체로 원자적이라 "쓰기 도중 죽으면 원본이 깨진다"는 실패 모드가 없다.
 *
 * <p><b>{@code draft}만 정규화하지 않는다</b>(ADR-73 예외). TTL로 소멸하는 임시 상태이고 쿼리 대상이
 * 아니며, 정규화하면 draft 전용 테이블 셋을 또 만들어야 한다. 그래서 이 클래스가 소유하는 것은 둘이다 —
 * <b>Jackson 왕복</b>(엔티티는 직렬화된 문자열까지만 안다, TΔ3b)과 <b>로드 경계 위생</b>(ADR-66).
 *
 * <h2>무결성 관문의 승계 범위 (TΔ8)</h2>
 * 파일 시절엔 읽기 경계가 <b>유일한</b> 관문이었지만 이제 스키마가 앞에서 일부를 받는다. 어느 쪽이
 * 무엇을 지는지 갈라 둔다 — 겹쳐 두면 DB가 이미 막은 것을 코드가 다시 세고, 빠뜨리면 관문이 샌다.
 * <table border="1">
 *   <caption>결손 유형별 소유자</caption>
 *   <tr><th>결손</th><th>막는 주체</th></tr>
 *   <tr><td>{@code mode} 부재·범위 밖</td><td><b>스키마</b> — {@code NOT NULL} + CHECK 2범주</td></tr>
 *   <tr><td>{@code created_at} 부재</td><td><b>스키마</b> — {@code NOT NULL}(TTL 판정의 필수 입력)</td></tr>
 *   <tr><td>{@code draft} 컬럼 부재</td><td><b>스키마</b> — {@code NOT NULL}</td></tr>
 *   <tr><td>{@code draft}가 JSON {@code null}·Note 아닌 형태</td><td><b>여기</b> — {@code jsonb NOT NULL}은
 *       {@code 'null'::jsonb}를 통과시키고, 형태는 애초에 컬럼이 모른다</td></tr>
 *   <tr><td>{@code draft.coffee_name} 공백 · 엔트리 0건</td><td><b>여기</b> — JSONB 내부는 제약 밖이다</td></tr>
 *   <tr><td>edit 모드의 {@code target}·{@code target.note_id} 결손</td><td><b>여기</b> — record 모드는
 *       {@code target}이 없는 것이 정상이라 컬럼을 {@code NOT NULL}로 만들 수 없다(조건부 필수)</td></tr>
 * </table>
 * 즉 <b>부재 판정이 컬럼으로 표현되는 것만</b> 스키마로 내려갔고, JSONB 내부와 모드 조건부 필수는 남는다.
 * 남은 것들은 psql 직접 편집으로 여전히 들어올 수 있다(FK가 없듯 여기도 DB가 대신 봐주지 않는다).
 *
 * <p><b>구 "draft.slug 공백" 검사는 승계하지 않는다</b> — id는 INSERT가 발급하므로 record 모드 신규
 * draft는 정상적으로 식별자가 없다(D-1). 그대로 옮겼다면 신규 pending이 전부 훼손 판정됐다(TΔ0b §4 E-5).
 *
 * <p><b>트랜잭션 경계를 선언하지 않는다.</b> 세 연산이 전부 단문이고({@code save}·{@code findById}·
 * {@code deleteById}) 묶어야 할 다중 쓰기가 없다 — 노트 저장소가 {@code @Transactional}을 지는 이유(3단
 * 중첩을 순서대로 심는다)가 여기엔 없다. 훼손 정리가 조회와 별개 트랜잭션인 것도 의도다: 그것은 조회의
 * 일부가 아니라 <b>자가 회복</b>이고, 롤백돼 살아남으면 다음 턴이 같은 행에 다시 걸린다.
 */
public class JpaPendingStore implements PendingStore {

    private static final Logger log = LoggerFactory.getLogger(JpaPendingStore.class);

    private final PendingNoteEntityRepository pendings;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final Clock clock;

    // 시계(Asia/Seoul — V-3, 노트 저장소와 동일)는 config 공통 빈 주입(ADR-63), 테스트에서 시간 고정용.
    public JpaPendingStore(PendingNoteEntityRepository pendings, ObjectMapper mapper, Duration ttl, Clock clock) {
        this.pendings = pendings;
        this.mapper = mapper;
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * pending 저장(덮어쓰기). {@code user_id}가 PK라 {@code save}가 그대로 upsert가 된다 —
     * 사용자당 최대 1건(NFR-6)이라는 계약이 기본키로 표현돼 있어 "기존 것을 먼저 지운다"가 필요 없다.
     */
    @Override
    public void put(String userId, PendingNote pending) {
        pendings.save(toEntity(userId, pending));
    }

    @Override
    public Optional<PendingNote> get(String userId) {
        Optional<PendingNoteEntity> found = pendings.findById(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PendingNoteEntity row = found.get();
        // POLICY: 저장소 읽기 경계가 무결성 관문 — 파싱 실패·필수 필드 결손 pending은 훼손으로 판정해
        //         경고 로그 + 행 정리 후 "대기 없음"으로 수렴한다(TTL 만료와 같은 부류, 다음 제안으로
        //         자가 회복). 소비처는 로드된 pending의 구조 무결성을 재검증하지 않는다
        //         (ref: specs/coffee-note-agent/plan.md#ADR-66, data-model.md#2.3).
        //         스키마와의 분담은 클래스 javadoc의 표가 소유한다.
        Note draft;
        try {
            draft = mapper.readValue(row.getDraft(), Note.class);
        } catch (JacksonException e) {
            return discardCorrupt(userId, "draft 파싱 실패: " + e.getMessage());
        }
        PendingNote pending = toDomain(row, draft);
        String defect = integrityDefect(pending);
        if (defect != null) {
            return discardCorrupt(userId, defect);
        }
        // POLICY: TTL 초과 pending은 유효한 대기로 취급하지 않는다 (ref: data-model.md#V-7, plan §5).
        // 만료는 훼손이 아니므로 행을 지우지 않는다 — 정리는 다음 put이 덮어쓰며 한다(파일 시절과 동일).
        if (isExpired(pending)) {
            return Optional.empty();
        }
        // 노트 읽기와 동일한 위생 — draft의 무효 beans/brews 요소를 저장 경로와 같은 정규화(V-8·V-14·V-15)로
        // 드롭한다. 앱이 쓴 데이터엔 no-op. 행은 다시 쓰지 않는다(메모리 정규화만) — 소비처는 draft의
        // 요소 무결성도 재검증하지 않는다 (ref: plan.md#ADR-66, JpaNoteRepository.sanitized 선례).
        Note sanitized = pending.draft().normalized();
        if (sanitized != pending.draft()) {
            log.warn("pending 로드 위생(ADR-66): draft 무효 요소 드롭 — user={} (DB 직접 편집 위반 추정, 행은 다시 쓰지 않음)",
                    userId);
            pending = pending.withDraft(sanitized);
        }
        return Optional.of(pending);
    }

    @Override
    public void clear(String userId) {
        // 없는 행은 조용히 지나간다(Spring Data 계약) — [취소]·만료 정리가 "이미 없음"으로 실패하지 않는다.
        pendings.deleteById(userId);
    }

    /**
     * 도메인 → 행. {@code draft}만 JSON 문자열로 접히고 나머지는 컬럼으로 펴진다(ADR-73).
     *
     * <p>POLICY: {@code created_at}은 <b>UTC 표기</b>로 눕힌다 (TΔ4 감사 타임스탬프 POLICY 승계 —
     * plan 대응은 {@code JpaAuditingConfig}가 소유한다). {@code timestamptz}는 오프셋을 저장하지 않아
     * 다시 읽으면 드라이버 표기(UTC)로 돌아오는데, 쓰기 표기를 맞추지 않으면 <b>같은 저장소가 두 표기를
     * 준다</b> — 영속성 컨텍스트에 살아 있는 행은 {@code +09:00}, 다시 읽은 행은 {@code Z}. 인스턴트는
     * 불변이므로 TTL 판정(V-7)은 어느 표기에서도 같다.
     */
    private PendingNoteEntity toEntity(String userId, PendingNote pending) {
        PendingNote.EditTarget target = pending.target();
        MatchInfo match = pending.match();
        return new PendingNoteEntity(
                userId,
                pending.mode(),
                mapper.writeValueAsString(pending.draft()),
                target == null ? null : target.noteId(),
                target == null ? null : target.date(),
                pending.dateConflict(),
                match == null ? null : match.type(),
                match == null ? null : match.noteId(),
                match == null ? null : match.date(),
                pending.previewTs(),
                pending.createdAt() == null ? null : pending.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
    }

    /** 행 → 도메인. 부재는 <b>전 컬럼 null</b>로만 표현된다 — 값이 하나라도 있으면 결손으로 넘겨 관문이 판정한다. */
    private static PendingNote toDomain(PendingNoteEntity row, Note draft) {
        return new PendingNote(row.getMode(), draft, toTarget(row), row.isDateConflict(), toMatch(row),
                row.getPreviewTs(), row.getCreatedAt());
    }

    private static PendingNote.EditTarget toTarget(PendingNoteEntity row) {
        return row.getTargetNoteId() == null && row.getTargetDate() == null
                ? null
                : new PendingNote.EditTarget(row.getTargetNoteId(), row.getTargetDate());
    }

    // match_type이 판별 컬럼이다 — NEW는 note_id·date가 없는 것이 정상이라(D-1) 나머지 둘로는 부재를 가릴 수 없다.
    private static MatchInfo toMatch(PendingNoteEntity row) {
        return row.getMatchType() == null
                ? null
                : new MatchInfo(row.getMatchType(), row.getMatchNoteId(), row.getMatchDate());
    }

    private boolean isExpired(PendingNote pending) {
        Duration age = Duration.between(pending.createdAt(), OffsetDateTime.now(clock));
        return age.compareTo(ttl) > 0;
    }

    /**
     * data-model §2.3 로드 무결성 집합 중 <b>스키마가 지지 못하는 몫</b> — 훼손 사유 문자열(정상이면 null).
     * <p>{@code mode}·{@code createdAt}의 부재 검사는 여기 없다: 컬럼이 {@code NOT NULL}이라 행으로 존재하는
     * 이상 채워져 있고, 같은 판정을 두 곳에 두면 어느 쪽이 실효인지 흐려진다(클래스 javadoc 표).
     */
    private String integrityDefect(PendingNote pending) {
        Note draft = pending.draft();
        // jsonb NOT NULL은 'null'::jsonb를 통과시킨다 — 컬럼은 있는데 값이 JSON null인 갈래가 여기서 걸린다.
        if (draft == null) {
            return "draft JSON null";
        }
        // coffee_name은 기록 정체성(record는 propose 시 필수, edit draft는 원본 노트 사본) — 결손이면 소비처가
        // 모델 대면 사유에 커피명 대신 null을 노출하므로 관문에서 막는다(RecordProposalValidator 필수 검증과 대칭).
        String coffeeName = Sourced.valueOrNull(draft.coffeeName());
        if (coffeeName == null || coffeeName.isBlank()) {
            return "draft.coffee_name 공백";
        }
        if (draft.entries().isEmpty()) {
            return "draft 엔트리 0건";
        }
        if (pending.mode() == PendingNote.Mode.EDIT) {
            if (pending.target() == null) {
                return "edit 모드 target 결손";
            }
            // target.note_id는 수정 대상의 정체성 — 결손이면 소비처가 갱신 대상을 찾을 수 없다. 게이트의
            // 같은 대상 판정(SinglePendingGate.requireSameEditTargetOrFree)이 id 비교에서 NPE로 새고,
            // 거부 사유에는 커피명 대신 리터럴 "null"이 노출된다(draft.coffee_name과 동일 부류, CR25-10).
            if (pending.target().noteId() == null) {
                return "edit 모드 target.note_id 결손";
            }
        }
        return null;
    }

    // 훼손 pending 정리 — 경고 로그 + 행 삭제 후 "대기 없음"으로 수렴(ADR-66 ②).
    private Optional<PendingNote> discardCorrupt(String userId, String reason) {
        log.warn("pending 로드 위생(ADR-66): 훼손 pending 정리 — {} (user={} 행 삭제)", reason, userId);
        try {
            pendings.deleteById(userId);
        } catch (DataAccessException e) {
            // 정리 실패도 "대기 없음"으로 degrade — 턴을 깨지 않는다(ADR-66 converge to empty).
            log.warn("훼손 pending 정리 실패 — 대기 없음으로 처리하고 진행: user={}", userId, e);
        }
        return Optional.empty();
    }
}
