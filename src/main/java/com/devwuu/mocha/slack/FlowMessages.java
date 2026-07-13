package com.devwuu.mocha.slack;

/**
 * 대화 흐름의 사용자 안내 문구 상수 — {@link DefaultConversationFlows}(façade)에서 분리해 한곳에 소유한다
 * (ADR-31, changes/0013). 문구는 구현 디테일이고 spec 결정이 아니다 — 단, 검색 시작·종료 안내(AC-34) 등
 * spec이 모카 톤을 요구하는 항목은 주석에 근거를 남긴다.
 * <p>모카 톤: {@code PreviewBlocks}와 같은 강아지 말투("멍" + 🐾).
 */
final class FlowMessages {

    private FlowMessages() {
    }

    static final String SAVE_DONE_CAPTION = "저장했어요 멍! 🐾"; // 배달하는 카드 JPG의 캡션(AC-Δ1)
    static final String SAVE_DONE_NO_IMAGE = "저장했어요 멍! 카드 이미지는 잠시 뒤에 다시 만들어 볼게요 🐾"; // 카드 렌더/전송 실패 폴백(AC-18)
    static final String NOTHING_TO_SAVE = "저장할 기록을 못 찾았어요 멍… 만료됐거나 이미 처리됐나 봐요 🐾"; // 만료/부재 안내(V-7)
    static final String CANCELED = "이번 기록은 지웠어요 멍! 다음에 또 마시면 불러주세요 🐾"; // 취소 안내
    static final String BROKEN_PENDING = "기록이 뭔가 이상해요 멍… 다시 보내주시겠어요? 🐾"; // 방어(엔트리/슬러그 결손)
    static final String NEW_NOTE_FAILED = "기록을 정리하다 문제가 생겼어요 멍… 잠시 뒤 다시 보내주시겠어요? 🐾"; // 추출/검색/전송 실패(plan §7)
    static final String REVISE_FAILED = "수정을 반영하다 문제가 생겼어요 멍… 다시 말씀해 주시겠어요? 🐾"; // 수정 병합/전송 실패(plan §7). 기존 pending은 보존
    static final String PHOTO_FAILED = "사진을 받다 문제가 생겼어요 멍… 다시 올려주시겠어요? 🐾"; // 다운로드/스테이징/전송 실패(plan §7)
    static final String UNSUPPORTED_FORMAT = "그 사진은 제가 읽을 수 없는 포맷이에요 멍… JPEG나 PNG로 다시 올려주시겠어요? 🐾"; // 매직바이트 미지원 포맷 거부 안내(ADR-29, V-12, AC-46)
    static final String NOT_A_RECORD = "저는 커피 감상을 기록하는 강아지예요 멍! 마신 커피 이야기를 들려주세요 🐾"; // 의도 게이트 other 판정 안내(AC-20)
    static final String PENDING_EXISTS = "확인을 기다리는 기록이 있어요 멍! 먼저 [저장]이나 [취소]로 마무리해 주세요 🐾"; // record+대기 존재 안내 — 단일 대기 원칙(FR-17, AC-30)
    static final String NOTHING_TO_REVISE = "지금 고칠 대기 기록이 없어요 멍… 새 커피 이야기면 그대로 들려주세요! 🐾"; // revise+대기 없음 안내(FR-17)
    // --- 검색 세션 멘트(FR-20, ADR-25, changes/0011 TΔ5) — 시작·종료 안내는 spec AC-34가 요구하는 모카 톤. ---
    static final String SEARCH_STARTED = "기록을 찾아볼게요 멍! 🐾"; // 검색 세션 시작 안내(AC-34)
    static final String SEARCH_FOUND_CAPTION = "이 기록이 맞나요 멍? 다 보셨으면 \"됐어\"라고 말해주세요 🐾"; // 단일 매치 카드 캡션(AC-31)
    static final String SEARCH_CANDIDATES_HEADER = "비슷한 기록이 여러 개예요 멍! 번호나 이름으로 골라주세요 🐾"; // 복수 후보 목록 머리말(AC-32)
    static final String SEARCH_REQUERY = "딱 맞는 기록을 못 찾았어요 멍… 날짜나 로스터리 같은 단서를 더 알려주시겠어요? 🐾"; // 무후보 재질문(AC-33)
    static final String SEARCH_LIMIT_REACHED = "이번엔 못 찾겠어요 멍… 찾기를 마칠게요. 다른 단서가 떠오르면 다시 불러주세요! 🐾"; // 재질문 상한 도달 → 세션 종료(FR-20/AC-33)
    static final String SEARCH_ENDED = "기록 찾기를 마칠게요 멍! 또 궁금하면 언제든 불러주세요 🐾"; // end 의도 종료 안내(AC-34)
    static final String SEARCH_FAILED = "기록을 찾다 문제가 생겼어요 멍… 다시 한 번 말씀해 주시겠어요? 🐾"; // 후보 선정 실패(plan §7) — 세션은 유지
    // --- 수정 세션 전환 멘트(FR-21, ADR-27, changes/0012 TΔ4) ---
    static final String EDIT_DATE_PROMPT_HEADER = "이 노트엔 기록이 여러 날 있어요 멍! 어느 날짜 기록을 고칠까요? 🐾"; // 엔트리 복수 → 날짜 목록 선택(AC-42)
    static final String EDIT_TARGET_GONE = "고치려던 기록을 못 찾았어요 멍… 사라졌나 봐요. 다시 찾아볼까요? 🐾"; // 대상 노트/엔트리 소실 → 수정 세션 미시작(plan §7)
    static final String EDIT_COFFEE_NAME_REJECTED =
            "커피 이름은 못 바꿔요 멍… 이름이 다르면 다른 커피예요! 그 커피는 새로 기록해 주세요 🐾"; // 커피명 변경 거부 + 새 등록 안내(V-9, AC-38)
    // 과거 참조 매치 실패 안내(FR-14, ADR-26, changes/0011 TΔ6) — 다음 의도(새 기록/검색)를 고르게 하고,
    // 보관이 10분뿐임을 명시해 TTL 폐기 후 일반 신규 처리로 흐르는 것이 놀랍지 않게 한다(AC-36).
    static final String REFERENCE_NOT_FOUND =
            "말씀하신 커피를 못 찾았어요 멍… \"새로 기록해줘\"라고 하면 방금 이야기 그대로 기록하고, "
                    + "\"찾아줘\"라고 하면 같이 찾아볼게요! 10분 동안 기억하고 있을게요 🐾"; // 매치 실패 → 전환 대기 안내
    // 버튼 1회 소진 상태 문구 — 미리보기 하단 버튼을 대체한다(spec AC-22 문구, changes/0009 ADR-20). 짧은 상태 배지라 강아지 톤은 절제.
    static final String FINALIZE_SAVED = "✅ 저장 완료"; // [저장] 완료 후 버튼 소진 상태 문구(AC-Δ1)
    static final String FINALIZE_CANCELED = "취소됨"; // [취소] 완료 후 버튼 소진 상태 문구(AC-Δ1)
}
