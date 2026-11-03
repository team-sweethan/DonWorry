-- add_fds_evaluation_merchant_id
-- 생성: 2026-10-26 17:49:10 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 fds · P5가 전달한 가맹점 스냅샷으로 반복 결제 탐지 범위를 분리한다.
-- 기존 FDS 평가는 개발 환경의 기존 기준 가맹점 1로 전환하며 P5 테이블을 조회하지 않는다.
ALTER TABLE fds_evaluations
    ADD COLUMN merchant_id BIGINT NULL AFTER payment_id;

UPDATE fds_evaluations
SET merchant_id = 1
WHERE merchant_id IS NULL;

ALTER TABLE fds_evaluations
    MODIFY COLUMN merchant_id BIGINT NOT NULL,
    DROP INDEX idx_fds_evaluations_customer_requested,
    ADD INDEX idx_fds_evaluations_merchant_customer_requested
        (merchant_id, customer_ref, payment_requested_at);

