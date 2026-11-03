-- create_payment_fds_recoveries_table
-- 생성: 2026-10-28 15:50:21 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 #273: 담당 ERD 반영 및 사용자 구현 승인.
CREATE TABLE payment_fds_recoveries (
    recovery_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    original_evaluation_request_id UUID NOT NULL,
    original_evaluation_id BIGINT NOT NULL,
    original_error_code VARCHAR(100) NOT NULL,
    original_evaluated_at DATETIME(6) NOT NULL,
    evaluation_requested_at DATETIME(6) NOT NULL,
    evaluation_responded_at DATETIME(6) NOT NULL,
    failure_report_id UUID NOT NULL,
    analysis_id BIGINT NULL,
    disposition VARCHAR(40) NULL,
    reason_code VARCHAR(100) NULL,
    analyzed_at DATETIME(6) NULL,
    new_evaluation_request_id UUID NULL,
    retry_id UUID NULL,
    requested_by BIGINT NULL,
    requested_at DATETIME(6) NULL,
    status VARCHAR(30) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (recovery_id),
    CONSTRAINT uk_pfr_payment_id UNIQUE (payment_id),
    CONSTRAINT uk_pfr_original_evaluation_request_id UNIQUE (original_evaluation_request_id),
    CONSTRAINT uk_pfr_failure_report_id UNIQUE (failure_report_id),
    CONSTRAINT uk_pfr_analysis_id UNIQUE (analysis_id),
    CONSTRAINT uk_pfr_new_evaluation_request_id UNIQUE (new_evaluation_request_id),
    CONSTRAINT uk_pfr_retry_id UNIQUE (retry_id),
    CONSTRAINT fk_pfr_payment FOREIGN KEY (payment_id) REFERENCES payments(payment_id),
    CONSTRAINT chk_pfr_values CHECK (
        payment_id > 0 AND original_evaluation_id > 0
        AND CHAR_LENGTH(TRIM(original_error_code)) > 0
        AND (analysis_id IS NULL OR analysis_id > 0)
        AND (requested_by IS NULL OR requested_by > 0)
        AND (reason_code IS NULL OR CHAR_LENGTH(TRIM(reason_code)) > 0)
        AND (new_evaluation_request_id IS NULL OR new_evaluation_request_id <> original_evaluation_request_id)
        AND (disposition IS NULL OR disposition IN ('REEVALUATION_AVAILABLE', 'EXISTING_RESULT_AVAILABLE',
            'PAYMENT_FOLLOW_UP_REQUIRED', 'MANUAL_REVIEW_REQUIRED', 'EVALUATION_NOT_FOUND'))
    ),
    CONSTRAINT chk_pfr_analysis CHECK ((analysis_id IS NULL AND disposition IS NULL AND reason_code IS NULL
            AND analyzed_at IS NULL) OR (analysis_id IS NOT NULL AND disposition IS NOT NULL
            AND reason_code IS NOT NULL AND analyzed_at IS NOT NULL)),
    CONSTRAINT chk_pfr_work CHECK ((new_evaluation_request_id IS NULL AND retry_id IS NULL AND requested_by IS NULL
            AND requested_at IS NULL) OR (new_evaluation_request_id IS NOT NULL AND retry_id IS NOT NULL
            AND requested_by IS NOT NULL AND requested_at IS NOT NULL)),
    CONSTRAINT chk_pfr_state CHECK (
        (status = 'WAITING_ANALYSIS' AND analysis_id IS NULL AND disposition IS NULL AND reason_code IS NULL
            AND analyzed_at IS NULL AND new_evaluation_request_id IS NULL AND retry_id IS NULL
            AND requested_by IS NULL AND requested_at IS NULL AND completed_at IS NULL)
        OR (status = 'READY' AND analysis_id IS NOT NULL AND disposition IS NOT NULL AND reason_code IS NOT NULL
            AND analyzed_at IS NOT NULL AND new_evaluation_request_id IS NULL AND retry_id IS NULL
            AND requested_by IS NULL AND requested_at IS NULL AND completed_at IS NULL
            AND disposition = 'REEVALUATION_AVAILABLE')
        OR (status = 'REQUESTED' AND analysis_id IS NOT NULL AND disposition IS NOT NULL
            AND reason_code IS NOT NULL AND analyzed_at IS NOT NULL AND new_evaluation_request_id IS NOT NULL
            AND retry_id IS NOT NULL AND requested_by IS NOT NULL AND requested_at IS NOT NULL
            AND completed_at IS NULL AND disposition = 'REEVALUATION_AVAILABLE')
        OR (status = 'COMPLETED' AND analysis_id IS NOT NULL AND disposition IS NOT NULL
            AND reason_code IS NOT NULL AND analyzed_at IS NOT NULL AND new_evaluation_request_id IS NOT NULL
            AND retry_id IS NOT NULL AND requested_by IS NOT NULL AND requested_at IS NOT NULL
            AND completed_at IS NOT NULL AND disposition = 'REEVALUATION_AVAILABLE')
        OR (status = 'MANUAL_REVIEW' AND analysis_id IS NOT NULL AND disposition IS NOT NULL
            AND reason_code IS NOT NULL AND analyzed_at IS NOT NULL AND completed_at IS NOT NULL
            AND (retry_id IS NULL OR disposition = 'REEVALUATION_AVAILABLE'))
    )
);
