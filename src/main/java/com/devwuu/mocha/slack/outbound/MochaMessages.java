package com.devwuu.mocha.slack.outbound;

/**
 * 모카 톤 사용자 안내 문구 상수 — 대화 경로 구현에서 분리해 한곳에 소유한다(ADR-31, changes/0013; 개칭 ADR-51). 문구는 구현 디테일이고 spec 결정이 아니다 — 단,
 * spec이 특정 문구·톤을 요구하는 항목은 주석에 근거를 남긴다.
 * <p>모카 톤: {@code PreviewBlocks}와 같은 강아지 말투("멍" + 🐾).
 */
public final class MochaMessages {

    private MochaMessages() {
    }

    public static final String SAVE_DONE_CAPTION = "저장했어요 멍! 🐾"; // 배달하는 카드 JPG의 캡션(AC-Δ1) — 첫 카드에만 싣는다(TΔ5b)
    public static final String SAVE_DONE_NO_IMAGE = "저장했어요 멍! 카드 이미지는 잠시 뒤에 다시 만들어 볼게요 🐾"; // 카드 렌더/전송 실패 폴백(AC-18)
    public static final String SAVE_DONE_PARTIAL_IMAGE = "그런데 카드 몇 장은 배달하지 못했어요 멍… 나중에 다시 찾아주시면 보내드릴게요 🐾"; // 회차 카드 일부 전송 실패 — 성공분은 배달됨(부분 폴백, plan §7·FR-16)
    public static final String NOTHING_TO_SAVE = "저장할 기록을 못 찾았어요 멍… 만료됐거나 이미 처리됐나 봐요 🐾"; // 만료/부재 안내(V-7)
    public static final String CANCELED = "이번 기록은 지웠어요 멍! 다음에 또 마시면 불러주세요 🐾"; // 취소 안내
    public static final String BROKEN_PENDING = "기록이 뭔가 이상해요 멍… 다시 보내주시겠어요? 🐾"; // 방어(엔트리/슬러그 결손)
    public static final String PHOTO_FAILED = "사진을 받다 문제가 생겼어요 멍… 다시 올려주시겠어요? 🐾"; // 다운로드/스테이징/전송 실패(plan §7)
    public static final String UNSUPPORTED_FORMAT = "그 사진은 제가 읽을 수 없는 포맷이에요 멍… JPEG나 PNG로 다시 올려주시겠어요? 🐾"; // 매직바이트 미지원 포맷 거부 안내(ADR-29, V-12, AC-46)
    // 버튼 1회 소진 상태 문구 — 미리보기 하단 버튼을 대체한다(spec AC-22 문구, changes/0009 ADR-20). 짧은 상태 배지라 강아지 톤은 절제.
    public static final String FINALIZE_SAVED = "✅ 저장 완료"; // [저장] 완료 후 버튼 소진 상태 문구(AC-Δ1)
    public static final String FINALIZE_CANCELED = "취소됨"; // [취소] 완료 후 버튼 소진 상태 문구(AC-Δ1)
    // --- 에이전트 턴 폴백 멘트(FR-25, ADR-48, changes/0018 TΔ7b) ---
    public static final String AGENT_TURN_FAILED =
            "말씀을 처리하다 문제가 생겼어요 멍… 다시 한 번 보내주시겠어요? 🐾"; // 턴 실패·상한 도달 폴백 — pending·노트 무변화 + 재요청 안내(AC-63)
}
