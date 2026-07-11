package com.devwuu.mocha.render;

/**
 * 사진 썸네일 생성 경계 (ref: plan.md §3, §5 {@code mocha.photo.thumb-width}, tasks T4-3).
 * <p>썸네일은 <b>파생물</b>이다 — {@code data/photos/<slug>/<date>/*} 원본에서 만들어 {@code artifact/thumbs/}
 * 아래에 쓰며, 통째로 지워도 리렌더로 재생성된다(ADR-1). 따라서 원본(source of truth)엔 아무것도 남기지 않는다.
 * <p>NoteRenderer(T5-1)가 노트를 렌더할 때 인덱스 카드용으로 호출한다.
 */
public interface ThumbnailGenerator {

    /**
     * 원본 사진에서 썸네일을 생성해 {@code artifact/thumbs/…}에 쓰고, artifact 기준 <b>상대 경로</b>를 반환한다.
     * <p>입력 경로는 JSON에 저장된 {@code photos/<slug>/<date>/<name>} 상대 경로(V-4)이며, {@code photos/}
     * 접두만 {@code thumbs/}로 치환해 원본 트리를 그대로 미러링한다.
     *
     * @param photoRelPath {@code photos/}로 시작하는 원본 사진 상대 경로.
     * @return {@code thumbs/}로 시작하는 썸네일 상대 경로 — HTML 링크·이미지 경로는 상대 경로만(AC-11).
     */
    String makeThumbnail(String photoRelPath);
}
