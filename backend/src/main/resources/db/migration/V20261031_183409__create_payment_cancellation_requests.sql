-- create_payment_cancellation_requests
-- 생성: 2026-10-31 18:34:09 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 #381: 병합된 저장 명세를 바탕으로 저장 기반 구현. 운영 실행 연결은 후속.
CREATE TABLE payment_cancellation_requests (
    request_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    request_key UUID NOT NULL,
    payment_id BIGINT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    resolution VARCHAR(30) NOT NULL,
    response_code VARCHAR(100) NULL,
    existing_void_action_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT uk_payment_cancellation_requests_merchant_key UNIQUE (merchant_id, request_key),
    CONSTRAINT uk_payment_cancellation_requests_id_payment UNIQUE (request_id, payment_id),
    CONSTRAINT fk_payment_cancellation_requests_payment FOREIGN KEY (payment_id) REFERENCES payments (payment_id),
    CONSTRAINT fk_payment_cancellation_requests_void_action FOREIGN KEY (existing_void_action_id) REFERENCES payment_actions (action_id),
    CONSTRAINT chk_payment_cancellation_requests_values CHECK (
        merchant_id > 0 AND CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 200 AND updated_at >= created_at),
    CONSTRAINT chk_payment_cancellation_requests_resolution CHECK (
        (resolution = 'DEFERRED' AND response_code IS NOT NULL AND response_code = 'PAYMENT_IN_PROGRESS' AND existing_void_action_id IS NULL)
        OR (resolution = 'REVIEW_REQUIRED' AND response_code IS NOT NULL AND response_code = 'PAYMENT_REVIEW_REQUIRED' AND existing_void_action_id IS NULL)
        OR (resolution = 'REJECTED' AND response_code IS NOT NULL AND response_code = 'PAYMENT_NOT_CANCELABLE' AND existing_void_action_id IS NULL)
        OR (resolution IN ('ACCEPTED', 'LINKED') AND response_code IS NULL AND existing_void_action_id IS NULL)
        OR (resolution = 'LEGACY_VOID' AND response_code IS NULL)
    ),
    INDEX idx_payment_cancellation_requests_payment (payment_id)
);
