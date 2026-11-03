-- add_payment_status_metadata
-- 생성: 2026-10-25 21:09:54 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- 과거 상태 변경 시각·거절 사유는 추정해서 채우지 않는다.
ALTER TABLE payments
    ADD COLUMN status_changed_at DATETIME(6) NULL,
    ADD COLUMN decline_code VARCHAR(100) NULL,
    ADD CONSTRAINT chk_payments_decline_code CHECK (
        decline_code IS NULL
        OR (status = 'DENIED' AND decline_code = 'FDS_REJECTED')
        OR (status = 'DECLINED' AND decline_code = 'AUTHORIZATION_DECLINED')
    ),
    ADD INDEX idx_payments_merchant_order (merchant_id, order_ref);
