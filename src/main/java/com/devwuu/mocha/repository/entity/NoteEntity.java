package com.devwuu.mocha.repository.entity;

import com.devwuu.mocha.domain.Source;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code note} 테이블 매핑 — 커피 1종 (ref: data-model.md#2.1, changes/0028-rdb-storage/delta.md#스키마).
 *
 * <p>POLICY: 도메인 {@link com.devwuu.mocha.domain.Note}(불변 record)를 엔티티로 바꾸지 않는다 — 영속 관심사가
 * 도메인으로 새지 않게 별도 클래스로 둔다(백엔드 CLAUDE.md §4, REVIEW.md §2). 양방향 변환은 이 패키지의
 * 변환기가 소유한다(TΔ3c).
 * <p>POLICY: <b>엔티티에 연관 매핑을 두지 않는다</b> — {@code @OneToMany}·{@code @ManyToOne}·{@code cascade}·
 * {@code orphanRemoval} 전부 미사용이고, 자식은 {@code note_id}를 평범한 {@code Long} 컬럼으로 들고 있다.
 * 관계 조회(조인·조립)는 QueryDSL이 질의 수준에서 푼다(사용자 확정 2026-07-30 — ADR-73 개정 대상, TΔ12).
 * DB에 FK가 없고 삭제 전파도 애플리케이션이 명시적 순서로 전담하므로(ref: delta.md#ADR-75, TΔ5d),
 * 엔티티 그래프를 만들면 <b>DB·저장소·매핑 세 곳이 각자의 관계 개념을 갖게 된다</b> — 관계를 아는 곳을
 * 질의 한 곳으로 모은다.
 *
 * <p>이름에 {@code Entity} 접미를 붙인 것은 도메인과 단순명이 겹치기 때문이다 — 변환기가 양쪽 타입을 한
 * 파일에서 다루는데(TΔ3c) 접미가 없으면 한쪽을 FQN으로 써야 한다. 노트 계열 5개·엔트리 계열 5개 전부
 * 같은 규칙으로 통일한다.
 */
@Entity
@Table(name = "note")
public class NoteEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // V-9: coffee_name은 노트 생성 후 불변이라 setter를 두지 않는다. V-5: source ∈ {user, photo}(NOT NULL).
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "coffee_name", nullable = false))
    @AttributeOverride(name = "source", column = @Column(name = "coffee_name_source", nullable = false, length = 16))
    private SourcedValue coffeeName;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "roastery"))
    @AttributeOverride(name = "source", column = @Column(name = "roastery_source", length = 16))
    private SourcedValue roastery;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "roast_level"))
    @AttributeOverride(name = "source", column = @Column(name = "roast_level_source", length = 16))
    private SourcedValue roastLevel;

    // Q-8: official_notes는 배열 전체에 source 하나 — 값은 note_official_note, source는 여기.
    @Enumerated(EnumType.STRING)
    @Column(name = "official_notes_source", length = 16)
    private Source officialNotesSource;

    // Q-6: 매칭(FR-14) 비교 키. Aliases.normalize()가 단일 소스이므로 값은 애플리케이션이 계산해 넣는다
    // (생성 컬럼으로 두면 정규화 로직이 SQL과 Java 두 곳에 생긴다 — REVIEW.md §1·§2).
    @Column(name = "coffee_name_normalized", nullable = false)
    private String coffeeNameNormalized;

    @Column(name = "roastery_normalized")
    private String roasteryNormalized;

    // 감사 컬럼 4종(Q-5)은 BaseEntity가 소유한다 — 값 주입은 Spring Data Auditing이 맡는다(TΔ4).

    // 하위 컬렉션은 여기 없다 — 자식(note_bean·note_official_note·note_alias·note_source)은
    // note_id로 각자 서 있고, 조립과 정렬(seq 오름차순 / 별칭은 id 오름차순)은 질의가 소유한다.

    protected NoteEntity() {
        // JPA 전용.
    }

    public NoteEntity(SourcedValue coffeeName, SourcedValue roastery, SourcedValue roastLevel,
                      Source officialNotesSource, String coffeeNameNormalized, String roasteryNormalized) {
        this.coffeeName = coffeeName;
        this.roastery = roastery;
        this.roastLevel = roastLevel;
        this.officialNotesSource = officialNotesSource;
        this.coffeeNameNormalized = coffeeNameNormalized;
        this.roasteryNormalized = roasteryNormalized;
    }

    public Long getId() {
        return id;
    }

    public SourcedValue getCoffeeName() {
        return coffeeName;
    }

    public SourcedValue getRoastery() {
        return roastery;
    }

    public SourcedValue getRoastLevel() {
        return roastLevel;
    }

    public Source getOfficialNotesSource() {
        return officialNotesSource;
    }

    public String getCoffeeNameNormalized() {
        return coffeeNameNormalized;
    }

    public String getRoasteryNormalized() {
        return roasteryNormalized;
    }

    // 수정 가능한 노트 단위 필드(FR-21) — coffee_name은 V-9 불변이라 제외한다.
    // 로스터리 변경은 정규화 비교 키(Q-6)도 함께 움직인다.
    public void updateRoastery(SourcedValue roastery, String roasteryNormalized) {
        this.roastery = roastery;
        this.roasteryNormalized = roasteryNormalized;
    }

    public void updateRoastLevel(SourcedValue roastLevel) {
        this.roastLevel = roastLevel;
    }

    public void updateOfficialNotesSource(Source officialNotesSource) {
        this.officialNotesSource = officialNotesSource;
    }
}
