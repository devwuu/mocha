package com.devwuu.mocha.slack.outbound;

/**
 * 모카 톤 사용자 안내 문구 상수 — 대화 경로 구현에서 분리해 한곳에 소유한다(ADR-31, changes/0013; 개칭 ADR-51). 문구는 구현 디테일이고 spec 결정이 아니다 — 단,
 * spec이 특정 문구·톤을 요구하는 항목은 주석에 근거를 남긴다.
 * <p>모카 톤: 강아지 말투("멍" + 🐾).
 * <p>0029 TΔ4에서 저장 커밋 체인의 문구가 통째로 사라졌다(카드 배달 캡션·폴백 2종·만료 안내·취소 안내·
 * 버튼 소진 상태 문구 2종) — [저장]/[취소] 버튼과 확인 미리보기가 pending과 함께 폐기되며 이 경로를 탈
 * 사용자가 없어졌다. 저장 확정의 안내는 앱의 폼이 가져간다(TΔ6·TΔ10).
 */
public final class MochaMessages {

    private MochaMessages() {
    }

    public static final String PHOTO_FAILED = "사진을 받다 문제가 생겼어요 멍… 다시 올려주시겠어요? 🐾"; // 다운로드/스테이징/전송 실패(plan §7)
    public static final String UNSUPPORTED_FORMAT = "그 사진은 제가 읽을 수 없는 포맷이에요 멍… JPEG나 PNG로 다시 올려주시겠어요? 🐾"; // 매직바이트 미지원 포맷 거부 안내(ADR-29, V-12, AC-46)
    // --- 에이전트 턴 폴백 멘트(FR-25, ADR-48, changes/0018 TΔ7b) ---
    public static final String AGENT_TURN_FAILED =
            "말씀을 처리하다 문제가 생겼어요 멍… 다시 한 번 보내주시겠어요? 🐾"; // 턴 실패·상한 도달 폴백 — 노트 무변화 + 재요청 안내(AC-63)
}
