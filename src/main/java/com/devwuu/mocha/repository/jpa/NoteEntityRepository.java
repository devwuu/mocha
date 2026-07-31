package com.devwuu.mocha.repository.jpa;

import com.devwuu.mocha.repository.entity.NoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 노트 애그리거트의 행 입출력 (ref: changes/0028-rdb-storage/delta.md#ADR-72 저장소 3분할, tasks.md TΔ5a).
 *
 * <p>Spring Data의 <b>custom fragment</b> 패턴이다 — 파생·기본 CRUD는 {@link JpaRepository}가, 자식 9종은
 * {@link NoteEntityRepositoryCustom}(QueryDSL)이 맡고 {@code extends}로 이어져 <b>바깥에는 인터페이스
 * 하나로 보인다</b>. {@code JpaNoteRepository}는 이것 하나만 의존한다.
 *
 * <p>둘의 경계는 자의적이지 않다: {@code JpaRepository<NoteEntity, Long>}의 파생 메서드는 {@code NoteEntity}
 * 에만 걸리므로 자식 접근은 <b>애초에 파생으로 표현되지 않는다</b>. 그래서 이 인터페이스가 직접 갖는 것은
 * {@code note} 행에 한정된다.
 *
 * <p>엔티티당 저장소를 두지 않는 이유는 연관 매핑 부재(TΔ3a) + 애플리케이션 전담 삭제 전파(ADR-74)가 곧
 * <b>Note가 애그리거트 경계</b>라는 뜻이기 때문이다.
 */
public interface NoteEntityRepository extends JpaRepository<NoteEntity, Long>, NoteEntityRepositoryCustom {

    /**
     * 전건 조회 — {@code id} 오름차순.
     * <p>slug가 폐기되면서(ADR-74) 파일 구현체가 쓰던 파일명 정렬이 근거를 잃었다. 대체 키는 삽입 순서를
     * 그대로 반영하는 {@code id}다 — 정렬을 걸지 않으면 Postgres는 힙 순서를 주므로 행을 한 번 수정한
     * 것만으로 순서가 밀린다.
     */
    List<NoteEntity> findAllByOrderByIdAsc();
}
