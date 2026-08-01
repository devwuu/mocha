package com.devwuu.mocha.web;

/**
 * 이 앱의 <b>고정 단일 사용자</b> 식별자 (ref: changes/0029 tasks.md TΔ6a·TΔ8a, delta.md §5 비범위).
 *
 * <p>모카는 1인용이고 인증은 이 델타의 범위 밖이다(A3). 그런데 서버의 사용자별 상태 — 트랜스크립트
 * 문맥({@code FoldingChatMemory})과 사진 스테이징 디렉터리 — 는 키를 하나 요구한다. 구 키는 Slack
 * userId였고, 그 입구가 사라지는 자리를 이 상수가 대신한다.
 *
 * <p><b>설정 키로 올리지 않는다</b>: 단일 사용자는 튜닝 파라미터가 아니라 이 델타의 전제이고, 두 곳
 * (턴·사진 업로드)이 <i>반드시 같은 값</i>을 써야 한다 — 각자 {@code @Value} 기본값을 들면 그 두 값이
 * 갈릴 수 있는 구조가 된다(스테이징에 올린 사진을 다른 키의 턴이 못 읽는다).
 *
 * <p>A3(멀티테넌시·인증)에서 이 자리를 실제 인증 주체가 대체한다 — 그때 지워야 할 곳이 여기 하나다.
 */
public final class SingleUser {

    private SingleUser() {
    }

    /** 파일시스템 경로 조각으로도 쓰이므로(스테이징 디렉터리) 경로 안전한 값이다. */
    public static final String ID = "local";
}
