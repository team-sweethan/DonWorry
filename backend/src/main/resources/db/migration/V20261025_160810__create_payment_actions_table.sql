-- create_payment_actions_table
-- 생성: 2026-10-25 16:08:10 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 직원 조치 저장. #141 사용자 승인: 저장소 ERD·ADR 기준, Notion 후속 반영.
CREATE TABLE payment_actions
(
    action_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    decision_id BIGINT NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    idempotency_key UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    capture_id BIGINT NULL,
    error_code VARCHAR(100) NULL,
    requested_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (action_id),
    CONSTRAINT uk_payment_actions_payment UNIQUE (payment_id),
    CONSTRAINT uk_payment_actions_decision UNIQUE (decision_id),
    CONSTRAINT uk_payment_actions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_payment_actions_payment FOREIGN KEY (payment_id) REFERENCES payments (payment_id),
    CONSTRAINT chk_payment_actions_decision CHECK (decision_id > 0),
    CONSTRAINT chk_payment_actions_capture CHECK (capture_id IS NULL OR capture_id > 0),
    CONSTRAINT chk_payment_actions_type CHECK (action_type IN ('CAPTURE', 'VOID')),
    CONSTRAINT chk_payment_actions_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_payment_actions_result CHECK (
        (status = 'PENDING' AND capture_id IS NULL AND error_code IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND error_code IS NULL AND completed_at IS NOT NULL
            AND ((action_type = 'CAPTURE' AND capture_id IS NOT NULL)
                OR (action_type = 'VOID' AND capture_id IS NULL)))
        OR (status = 'FAILED' AND capture_id IS NULL AND error_code IS NOT NULL
            AND CHAR_LENGTH(TRIM(error_code)) > 0 AND completed_at IS NOT NULL)
    )
);
