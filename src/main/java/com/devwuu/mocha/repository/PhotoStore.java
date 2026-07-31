package com.devwuu.mocha.repository;

import java.util.List;

/**
 * 사진 원본 저장소 (ref: plan.md §3, data-model.md#2.2/2.4, tasks T4-1).
 * <p>사진은 텍스트보다 먼저(혹은 분리) 도착할 수 있고 그 시점엔 노트 폴더가 아직 확정되지 않는다(FR-10).
 * 그래서 수신 즉시 원본을 <b>임시 스테이징</b>에 두었다가, 사용자 [저장] 확인으로 폴더·날짜가 확정될 때
 * {@code photos/<noteFolder>/<date>/}로 옮긴다 — 확인 전 파일이 노트 트리에 새지 않게 하고, [취소]/TTL 시
 * 스테이징만 지우면 되도록 한다(ADR-3 정신: 확인 이후에만 커밋).
 * <p><b>{@code noteFolder}는 id가 아니라 {@link NoteFolderName}이 만든 접미 문자열</b>({@code <id>-<로스터리>-<커피명>})
 * 이다(changes/0028 §파일 경로 규약). 폴더명이 생성 시점 스냅샷이라 나중에 재계산으로 맞출 수 없기 때문이다 —
 * id만 받으면 로스터리가 수정된 뒤 {@link #moveEntryPhotos}가 기존 폴더를 찾지 못한다.
 * <p>JSON에 남기는 것은 절대 경로·URL이 아니라 {@code photos/}로 시작하는 상대 경로뿐이다(V-4, AC-11).
 * <p>구현: {@link LocalPhotoStore}. 썸네일 생성(makeThumbnail)은 파생물 계층이라 T4-3에서 붙인다.
 */
public interface PhotoStore {

    /**
     * pending 단계: 수신한 사진 원본을 사용자별 임시 스테이징에 저장한다(노트 폴더 미확정).
     *
     * @param userId   보낸 사용자 id (단일 사용자 전제, 스테이징 격리 키).
     * @param filename Slack 원본 파일명 — 안전 문자로 정규화해 확장자를 보존한다.
     * @param bytes    원본 바이트.
     * @return 스테이징된 파일의 최종 파일명(스테이징 내 충돌 시 {@code -N} 접미 부여됨).
     */
    String stage(String userId, String filename, byte[] bytes);

    /**
     * pending 이전 단계: 사용자 스테이징에 쌓인 사진 원본을 파일명 오름차순으로 읽어 돌려준다.
     * <p>수신 사진 OCR([2.5], FR-19)이 vision 입력으로 소비한다 — 스테이징이 없거나 비면 빈 목록.
     * 읽기만 하며 스테이징을 비우지 않는다(소비/폐기는 [저장]/[취소]/TTL이 정한다).
     *
     * @return 스테이징된 사진(name·bytes), 파일명 오름차순. commit 순서와 동일 정렬.
     */
    List<StagedImage> readStaged(String userId);

    /**
     * [저장] 확정: 스테이징된 사진들을 {@code photos/<noteFolder>/<date>/}로 이동하고 스테이징을 비운다.
     *
     * @param noteFolder {@link NoteFolderName}이 만든 폴더 접미. 신규 노트는 id가 INSERT 후에야 생기므로
     *                   이 호출이 저장 커밋 뒤로 밀린다(changes/0028 §파일 경로 규약, TΔ9b).
     * @return 상대 경로 목록({@code photos/...}), 파일명 오름차순. (V-4)
     */
    List<String> commit(String userId, String noteFolder, String date);

    /** [취소]/TTL 정리: 해당 사용자의 스테이징을 폐기한다(노트 트리는 건드리지 않는다). */
    void discard(String userId);

    /**
     * 수정 세션 날짜 이동 시 그 엔트리의 아카이브 폴더를 새 날짜로 동반 이동한다(ADR-32, FR-21).
     * <p>{@code photos/<noteFolder>/<fromDate>/}의 파일 전부를 {@code photos/<noteFolder>/<toDate>/}로 옮기고
     * 빈 옛 폴더를 제거한다 — 폴더=진실 불변식을 유지하기 위함이다. 대상 폴더에 같은 이름 파일이 있으면
     * {@code -N} 접미로 유일화해 병합하고(유실 없음), 원본 폴더가 아예 없으면 아무 일도 하지 않는다(no-op).
     * <p>호출부는 best-effort로 감싼다 — 이동 실패는 커밋을 되돌리지 않고 사진은 옛 폴더에 잔류한다
     * (아카이브로서 유효, 옛 카드 삭제와 동일 정책). (ref: plan.md#ADR-32, §7)
     *
     * @param noteFolder 대상 노트의 폴더 접미 — <b>지금 이름으로 재계산한 값이 아니라 그 폴더가 만들어질 때의
     *                   이름</b>이어야 한다(생성 시점 스냅샷, changes/0028 파생 결정 2).
     * @param fromDate   옮길 원본 날짜 폴더(YYYY-MM-DD).
     * @param toDate     이동처 날짜 폴더(YYYY-MM-DD).
     */
    void moveEntryPhotos(String noteFolder, String fromDate, String toDate);

    /**
     * 스테이징에 원본이 남아 있는 사용자 키 목록(스테이징 하위 디렉토리명). 없으면 빈 목록.
     * <p>앱 시작 시 고아 청소(ADR-29)가 이 목록을 pending·buffer 참조와 대조해 미참조 스테이징을 걸러낸다.
     * 반환값은 {@code stage} 시 정규화된 안전 키(단일 사용자 Slack id 전제상 {@code userId}와 동치)다.
     *
     * @return 스테이징 사용자 키, 오름차순.
     */
    List<String> stagedUserIds();
}
