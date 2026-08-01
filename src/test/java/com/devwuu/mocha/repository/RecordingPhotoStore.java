package com.devwuu.mocha.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 사진 저장소 손 fake — <b>무엇이 스테이징에 닿았고 무엇이 폐기됐는가</b>만 붙든다
 * (changes/0029 TΔ8a, {@code org.mockito} 미도입 방침 유지).
 *
 * <p>공용으로 둔 이유는 같은 질문을 <b>여러 층이 각자의 자리에서</b> 묻기 때문이다 — 업로드 유스케이스
 * ({@code PhotoServiceTest}), 그 REST 표면({@code PhotoControllerTest}), 취소가 스테이징을 비우는가
 * ({@code AgentTurnControllerTest}), 그리고 저장 확정·삭제가 아카이브에 무엇을 했는가
 * ({@code NoteServiceTest}, TΔ8b). 층마다 fake를 다시 지으면 그것들이 조용히 갈린다.
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

    /** 저장 확정이 받은 (폴더 접미, 날짜) — 폴더를 재계산했는지 되읽었는지가 여기서 갈린다(TΔ8b). */
    public final List<String> committedFolders = new ArrayList<>();
    public final List<String> committedDates = new ArrayList<>();

    /** 확정이 돌려줄 상대 경로. 비어 있으면 "스테이징에 아무것도 없었다"와 같다. */
    public List<String> committedPaths = List.of();

    /** 삭제가 지우라고 넘긴 상대 경로(TΔ8b). */
    public final List<String> deleted = new ArrayList<>();

    /** 확정 호출에서 던질 실패 — "사진이 실패해도 저장은 유지된다"를 묻는 자리가 쓴다. */
    public RuntimeException commitFailure;

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
        committedFolders.add(noteFolder);
        committedDates.add(date);
        if (commitFailure != null) {
            throw commitFailure;
        }
        staged.clear();
        return committedPaths;
    }

    @Override
    public void deletePhotos(List<String> relativePaths) {
        if (relativePaths != null) {
            deleted.addAll(relativePaths);
        }
    }

    /** 날짜 이동이 받은 (폴더 접미, 옛 날짜, 새 날짜) — 인자 3종을 한 줄로 붙여 기록한다(TΔ5b-2). */
    public final List<String> movedEntries = new ArrayList<>();

    /** 이동이 돌려줄 {@code 옛 경로 → 새 경로}. 비어 있으면 "옮길 사진이 없었다"와 같다. */
    public Map<String, String> movedPaths = Map.of();

    /** 이동 호출에서 던질 실패 — "파일 이동 실패는 수정을 되돌린다"를 묻는 자리가 쓴다. */
    public RuntimeException moveFailure;

    @Override
    public Map<String, String> moveEntryPhotos(String noteFolder, String fromDate, String toDate) {
        movedEntries.add(noteFolder + " " + fromDate + " → " + toDate);
        if (moveFailure != null) {
            throw moveFailure;
        }
        return movedPaths;
    }

    @Override
    public List<String> stagedUserIds() {
        return List.of();
    }
}
