-- changes/0029-app-interface TΔ4 — 확인 대기(pending)의 서버 상태 폐기.
--
-- pending은 "서버가 사용자당 1건의 작성 중 노트를 소유한다"는 모델이었다. 앱(SPA)에서는 작성 중 데이터가
-- 폼이므로 클라이언트가 소유하고, 서버는 턴마다 draft를 입력으로 받아(TΔ2 TurnDraft) 제안 결과를 응답으로
-- 돌려주기만 한다 — 서버가 기억할 것이 없다(open-questions.md OQ-1, delta 0029 D-2).
--
-- 함께 소멸하는 것: TTL(mocha.pending.ttl)·V-7 만료 판정·ADR-66 로드 경계 위생·단일 대기 슬롯(FR-17).
-- 되돌릴 수 없다 — 이 테이블의 행은 "아직 저장되지 않은 작성 중 데이터"라서 노트로 승격된 적이 없고,
-- 남아 있던 행은 어차피 TTL로 폐기될 것들이다(원본 데이터 손실 없음).
DROP TABLE IF EXISTS pending_note;
