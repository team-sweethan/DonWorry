-- add_fds_evaluation_merchant_id
-- 생성: 2026-10-26 11:02:57 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 fds · 반복 결제 규칙을 가맹점 경계 안에서 계산하기 위한 P5 요청 스냅샷
ALTER TABLE fds_evaluations
    ADD COLUMN merchant_id BIGINT NULL AFTER payment_id;

UPDATE fds_evaluations evaluation
JOIN payments payment ON payment.payment_id = evaluation.payment_id
SET evaluation.merchant_id = payment.merchant_id;

ALTER TABLE fds_evaluations
    MODIFY COLUMN merchant_id BIGINT NOT NULL,
    DROP INDEX idx_fds_evaluations_customer_requested,
    ADD INDEX idx_fds_evaluations_merchant_customer_requested
        (merchant_id, customer_ref, payment_requested_at);
