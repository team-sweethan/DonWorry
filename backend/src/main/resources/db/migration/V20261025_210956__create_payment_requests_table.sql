-- create_payment_requests_table
-- 생성: 2026-10-25 21:09:56 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

CREATE TABLE payment_requests (
    request_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_order_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    attempt_no BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    evaluation_request_id UUID NOT NULL,
    processing_stage VARCHAR(30) NOT NULL,
    evaluation_id BIGINT NULL,
    evaluation_result VARCHAR(20) NULL,
    evaluation_risk_score INT NULL,
    evaluation_error_code VARCHAR(100) NULL,
    evaluated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT uk_payment_requests_merchant_key UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT uk_payment_requests_order_attempt UNIQUE (payment_order_id, attempt_no),
    CONSTRAINT uk_payment_requests_payment UNIQUE (payment_id),
    CONSTRAINT uk_payment_requests_evaluation UNIQUE (evaluation_request_id),
    CONSTRAINT fk_payment_requests_order FOREIGN KEY (payment_order_id, merchant_id)
        REFERENCES payment_orders (payment_order_id, merchant_id),
    CONSTRAINT fk_payment_requests_payment FOREIGN KEY (payment_id) REFERENCES payments (payment_id),
    CONSTRAINT chk_payment_requests_attempt CHECK (attempt_no > 0),
    -- IS NOT NULL을 명시해 SQL CHECK의 UNKNOWN 통과를 막는다.
    CONSTRAINT chk_payment_requests_stage CHECK (
        (processing_stage = 'RECEIVED' AND evaluation_id IS NULL AND evaluation_result IS NULL
            AND evaluation_risk_score IS NULL AND evaluation_error_code IS NULL AND evaluated_at IS NULL)
        OR (processing_stage IN ('EVALUATED', 'AUTHORIZED') AND evaluation_id IS NOT NULL AND evaluation_id > 0
            AND evaluation_result IS NOT NULL AND evaluation_result IN ('ALLOW', 'REVIEW', 'DENY')
            AND evaluation_risk_score IS NOT NULL AND evaluation_risk_score >= 0
            AND evaluation_error_code IS NULL AND evaluated_at IS NOT NULL)
        OR (processing_stage = 'NEEDS_REVIEW' AND evaluation_id IS NOT NULL AND evaluation_id > 0
            AND evaluation_result IS NOT NULL AND evaluation_result = 'ERROR' AND evaluation_risk_score IS NULL
            AND evaluation_error_code IS NOT NULL AND CHAR_LENGTH(TRIM(evaluation_error_code)) > 0
            AND evaluated_at IS NOT NULL)
    )
);
