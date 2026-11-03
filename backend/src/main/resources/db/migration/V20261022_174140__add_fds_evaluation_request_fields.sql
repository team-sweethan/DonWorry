-- add_fds_evaluation_request_fields
-- 생성: 2026-10-22 17:41:40 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5가 보낸 평가 요청을 보존한다. payment_requested_at은 반복 결제 룰의 시간 기준이다.
-- 기존 평가 이력은 payments의 고객·생성 시각을 원래 요청 스냅샷으로 사용하고, 요청 ID는 새 UUID로 부여한다.
-- 먼저 NULL 허용으로 추가·백필한 뒤 제약을 적용해 기존 행이 있어도 전환된다.
ALTER TABLE fds_evaluations
    ADD COLUMN evaluation_request_id UUID         NULL AFTER evaluation_id,
    ADD COLUMN customer_ref          VARCHAR(100) NULL AFTER payment_id,
    ADD COLUMN payment_requested_at DATETIME(6)  NULL AFTER customer_ref;

UPDATE fds_evaluations evaluation
JOIN payments payment ON payment.payment_id = evaluation.payment_id
SET evaluation.evaluation_request_id = UUID(),
    evaluation.customer_ref = payment.customer_ref,
    evaluation.payment_requested_at = payment.created_at;

ALTER TABLE fds_evaluations
    MODIFY COLUMN evaluation_request_id UUID         NOT NULL,
    MODIFY COLUMN customer_ref          VARCHAR(100) NOT NULL,
    MODIFY COLUMN payment_requested_at DATETIME(6)  NOT NULL,
    ADD CONSTRAINT uk_fds_evaluations_request_id UNIQUE (evaluation_request_id),
    ADD INDEX idx_fds_evaluations_customer_requested (customer_ref, payment_requested_at);
