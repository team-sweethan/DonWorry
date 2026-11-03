-- create_payment_cancellations
-- 생성: 2026-10-31 18:34:16 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 #381: 병합된 저장 명세를 바탕으로 저장 기반 구현. 운영 실행 연결은 후속.
CREATE TABLE payment_cancellations (
    cancellation_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    source_request_id BIGINT NOT NULL,
    operation VARCHAR(10) NOT NULL,
    amount BIGINT NOT NULL,
    capture_id BIGINT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(100) NULL,
    accepted_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (cancellation_id),
    CONSTRAINT uk_payment_cancellations_payment UNIQUE (payment_id),
    CONSTRAINT uk_payment_cancellations_source_request UNIQUE (source_request_id),
    CONSTRAINT fk_payment_cancellations_payment FOREIGN KEY (payment_id) REFERENCES payments (payment_id),
    CONSTRAINT fk_payment_cancellations_source FOREIGN KEY (source_request_id, payment_id)
        REFERENCES payment_cancellation_requests (request_id, payment_id),
    CONSTRAINT chk_payment_cancellations_values CHECK (amount > 0 AND updated_at >= accepted_at
        AND (completed_at IS NULL OR (completed_at >= accepted_at AND completed_at <= updated_at))),
    CONSTRAINT chk_payment_cancellations_operation CHECK (
        (operation = 'VOID' AND capture_id IS NULL)
        OR (operation = 'REFUND' AND capture_id IS NOT NULL AND capture_id > 0)),
    CONSTRAINT chk_payment_cancellations_result CHECK (
        (status IN ('PROCESSING', 'UNKNOWN') AND completed_at IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND error_code IS NOT NULL AND CHAR_LENGTH(TRIM(error_code)) > 0))
);
