package com.devwuu.mocha.repository.jpa;

import com.devwuu.mocha.repository.entity.PendingNoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 확인 대기 노트의 행 입출력 (ref: changes/0028-rdb-storage/tasks.md TΔ8, delta.md#ADR-74).
 *
 * <p><b>노트가 쓴 3분할(ADR-73)이 여기엔 적용되지 않는다.</b> 그 분할의 근거는 자식 9종의 조인·조립·정렬이
 * 질의로 내려온다는 것인데, pending은 <b>테이블 하나에 사용자당 한 행</b>이고 중첩은 전부 {@code draft}
 * JSONB 안에 접혀 있다(ADR-74 예외) — 자식 행이 없으니 QueryDSL fragment가 맡을 일이 없고 파생 CRUD가
 * 그대로 전부다. 그래서 가운데 층({@code JpaPendingStore})이 아는 것도 정책 하나가 아니라
 * <b>로드 경계 위생 + Jackson 왕복</b>뿐이다.
 *
 * <p>PK는 {@code user_id}(String)다 — 단일 사용자 전제(NFR-6)에서 사용자당 최대 1건이라는 계약이 곧
 * 기본키다. A1에서 값은 단일이고 멀티테넌시는 A3다.
 */
public interface PendingNoteEntityRepository extends JpaRepository<PendingNoteEntity, String> {
}
