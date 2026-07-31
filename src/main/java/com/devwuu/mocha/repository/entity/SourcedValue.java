package com.devwuu.mocha.repository.entity;

import com.devwuu.mocha.domain.Source;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * 출처 표시 필드({@link com.devwuu.mocha.domain.Sourced})의 영속 형태 — (value, source) 두 컬럼으로 떨어진다
 * (ref: changes/0028-rdb-storage/delta.md#ADR-73).
 * <p>도메인 {@code Sourced<T>}는 제네릭이지만 컬럼으로 내려가는 것은 {@code Sourced<String>}뿐이다 —
 * {@code officialNotes}({@code Sourced<List<String>>})는 값이 {@code note_official_note} 테이블로,
 * source는 {@code note.official_notes_source} 컬럼으로 갈라진다(Q-8). 그래서 이 임베더블은 비제네릭이다.
 * <p>컬럼명은 쓰는 쪽에서 {@code @AttributeOverride}로 준다 — 같은 형태가 노트 3곳·원두 2곳에 붙는다.
 *
 * <p>POLICY: source는 enum 상수명(USER/PHOTO/SEARCH)으로 저장한다 — {@code Source.json()}의 소문자 표기는
 * JSON 계약 전용이고, DB는 스키마 CHECK가 대문자 상수명을 기대한다
 * (ref: specs/coffee-note-agent/changes/0028-rdb-storage/tasks.md TΔ2).
 */
@Embeddable
public record SourcedValue(String value, @Enumerated(EnumType.STRING) Source source) {
}
