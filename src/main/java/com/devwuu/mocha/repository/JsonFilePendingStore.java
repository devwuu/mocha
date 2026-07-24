package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code data/pending.json} 파일 기반 PendingStore (ref: plan.md#ADR-3).
 * <p>단일 파일(단일 사용자 NFR-6). 쓰기는 임시 파일 → move(replace) 원자적 반영으로
 * 쓰기 도중 죽어도 원본이 깨지지 않게 한다(CLAUDE.md §3). get은 TTL 초과분을 만료 처리한다(V-7).
 */
public class JsonFilePendingStore implements PendingStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFilePendingStore.class);

    private final Path pendingFile;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final Clock clock;

    // 시계(Asia/Seoul — V-3, NoteRepository와 동일)는 config 공통 빈 주입(ADR-63), 테스트에서 시간 고정용.
    public JsonFilePendingStore(Path dataDir, ObjectMapper mapper, Duration ttl, Clock clock) {
        this.pendingFile = dataDir.resolve("pending.json");
        this.mapper = mapper;
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void put(String userId, PendingNote pending) {
        try {
            Path dir = pendingFile.getParent();
            Files.createDirectories(dir);
            // 같은 디렉토리에 임시 파일로 쓴 뒤 원자적 move.
            Path tmp = Files.createTempFile(dir, "pending-", ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(pending));
            try {
                Files.move(tmp, pendingFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, pendingFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("pending 저장 실패: " + pendingFile, e);
        }
    }

    @Override
    public Optional<PendingNote> get(String userId) {
        if (!Files.isRegularFile(pendingFile)) {
            return Optional.empty();
        }
        // 일시적 읽기 오류(디스크·권한)는 훼손이 아니다 — 파일을 지우지 않고 전파한다(기존 read() 동작 유지).
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(pendingFile);
        } catch (IOException e) {
            throw new UncheckedIOException("pending 읽기 실패: " + pendingFile, e);
        }
        // POLICY: 저장소 읽기 경계가 유일한 무결성 관문 — 파싱 실패·필수 필드 결손 pending은 훼손으로
        //         판정해 경고 로그 + 파일 정리 후 "대기 없음"으로 수렴한다(TTL 만료와 같은 부류, 다음
        //         제안으로 자가 회복). 소비처는 로드된 pending의 구조 무결성을 재검증하지 않는다
        //         (ref: specs/coffee-note-agent/plan.md#ADR-66, data-model.md#2.3).
        PendingNote pending;
        try {
            pending = mapper.readValue(bytes, PendingNote.class);
        } catch (JacksonException e) {
            return discardCorrupt("파싱 실패: " + e.getMessage());
        }
        String defect = integrityDefect(pending);
        if (defect != null) {
            return discardCorrupt(defect);
        }
        // POLICY: TTL 초과 pending은 유효한 대기로 취급하지 않는다 (ref: data-model.md#V-7, plan §5).
        if (isExpired(pending)) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    @Override
    public void clear(String userId) {
        try {
            Files.deleteIfExists(pendingFile);
        } catch (IOException e) {
            throw new UncheckedIOException("pending 폐기 실패: " + pendingFile, e);
        }
    }

    private boolean isExpired(PendingNote pending) {
        Duration age = Duration.between(pending.createdAt(), OffsetDateTime.now(clock));
        return age.compareTo(ttl) > 0;
    }

    // data-model §2.3 로드 무결성 집합 — 훼손 사유 문자열(정상이면 null).
    // 최상위·엔트리 null은 JSON `null`/필드 부재에서 나올 수 있어 여기서 방어한다(도메인 record는 entries를
    // 정규화하지 않는다 — Note는 beans만). 무결성 검사 자체가 NPE로 새면 로드 경계 관문이 무력화된다.
    private String integrityDefect(PendingNote pending) {
        if (pending == null) {
            return "최상위 null";
        }
        if (pending.mode() == null) {
            return "mode 필드 결손";
        }
        Note draft = pending.draft();
        if (draft == null) {
            return "draft 필드 결손";
        }
        if (draft.slug() == null || draft.slug().isBlank()) {
            return "draft.slug 공백";
        }
        if (draft.entries() == null || draft.entries().isEmpty()) {
            return "draft 엔트리 0건";
        }
        if (pending.mode() == PendingNote.Mode.EDIT && pending.target() == null) {
            return "edit 모드 target 결손";
        }
        return null;
    }

    // 훼손 pending 정리 — 경고 로그 + 파일 삭제 후 "대기 없음"으로 수렴(ADR-66 ②).
    private Optional<PendingNote> discardCorrupt(String reason) {
        log.warn("pending 로드 위생(ADR-66): 훼손 pending 정리 — {} ({} 삭제)", reason, pendingFile.getFileName());
        try {
            Files.deleteIfExists(pendingFile);
        } catch (IOException e) {
            // 정리 실패(읽기 전용 디렉토리 등)도 "대기 없음"으로 degrade — 턴을 깨지 않는다(ADR-66 converge to empty).
            log.warn("훼손 pending 정리 실패 — 대기 없음으로 처리하고 진행: {}", pendingFile, e);
        }
        return Optional.empty();
    }
}
