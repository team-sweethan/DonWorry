-- index_settlement_cash_flow_queries
-- 생성: 2026-11-02 11:26:39 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P6 #230: 가맹점별 완료 누적/기간 조회와 예정·미정·취소 조회의 전역 스캔을 피한다.
ALTER TABLE settlements
    ADD INDEX idx_settlements_merchant_completed (merchant_id, status, completed_at, settlement_amount),
    ADD INDEX idx_settlements_merchant_scheduled (merchant_id, status, scheduled_at, expected_settlement_at);
