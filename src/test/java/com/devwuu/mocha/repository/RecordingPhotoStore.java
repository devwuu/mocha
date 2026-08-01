package com.devwuu.mocha.repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 사진 저장소 손 fake — <b>무엇이 스테이징에 닿았고 무엇이 폐기됐는가</b>만 붙든다
 * (changes/0029 TΔ8a, {@code org.mockito} 미도입 방침 유지).
 *
 * <p>공용으로 둔 이유는 같은 질문을 <b>세 층이 각자의 자리에서</b> 묻기 때문이다 — 업로드 유스케이스
 * ({@code PhotoServiceTest}), 그 REST 표면({@code PhotoControllerTest}), 취소가 스테이징을 비우는가
 * ({@code AgentTurnControllerTest}). 층마다 fake를 다시 지으면 그 셋이 조용히 갈린다.
 *
 * <p>파일 I/O 자체(원자적 쓰기·경로 규칙·이름 유일화)는 여기서 흉내 내지 않는다 — 그것은
 * {@code LocalPhotoStoreTest}가 실제 디렉터리로 검증하는 몫이고, 인메모리 대체의 그린은 아무것도
 * 보증하지 않는다(백엔드 CLAUDE.md §5.2).
 */
public class RecordingPhotoStore implements PhotoStore {

    /** 스테이징에 닿은 사진 — 파일명은 정규화 없이 그대로 둔다(그 규칙은 실 구현의 몫이다). */
    public final List<StagedImage> staged = new ArrayList<>();

    /** 폐기 호출을 받은 사용자 키. */
    public final List<String> discarded = new ArrayList<>();

    /** 스테이징 열람 횟수 — "부를 것이 없으면 파일 I/O도 없다"를 단언하는 자리가 쓴다. */
    public int reads;

    @Override
    public String stage(String userId, String filename, byte[] bytes) {
        staged.add(new StagedImage(filename, bytes));
        return filename;
    }

    @Override
    public List<StagedImage> readStaged(String userId) {
        reads++;
        return List.copyOf(staged);
    }

    @Override
    public void discard(String userId) {
        discarded.add(userId);
        staged.clear();
    }

    @Override
    public List<String> commit(String userId, String noteFolder, String date) {
        return List.of();
    }

    @Override
    public void moveEntryPhotos(String noteFolder, String fromDate, String toDate) {
    }

    @Override
    public List<String> stagedUserIds() {
        return List.of();
    }
}
