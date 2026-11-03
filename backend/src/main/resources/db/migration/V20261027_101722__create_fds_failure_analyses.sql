-- create_fds_failure_analyses
-- 생성: 2026-10-27 10:17:22 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 fds · P5가 보고한 P2 내부 평가 오류를 분석한 이력.
-- payment_id 등은 P5 소유 데이터의 논리 참조이므로 FK를 만들지 않는다.
CREATE TABLE fds_failure_analyses
(
    analysis_id                     BIGINT       NOT NULL AUTO_INCREMENT,
    failure_report_id               UUID         NOT NULL,
    original_evaluation_request_id  UUID         NOT NULL,
    evaluation_request_id           UUID         NOT NULL,
    evaluation_id                   BIGINT       NOT NULL,
    payment_id                      BIGINT       NOT NULL,
    merchant_id                     BIGINT       NOT NULL,
    customer_ref                    VARCHAR(100) NOT NULL,
    requested_amount                BIGINT       NOT NULL,
    payment_requested_at            DATETIME(6)  NOT NULL,
    evaluation_result               VARCHAR(20)  NULL,
    error_code                      VARCHAR(100) NULL,
    sanitized_error_message         VARCHAR(500) NULL,
    requested_at                    DATETIME(6)  NOT NULL,
    responded_at                    DATETIME(6)  NULL,
    payment_status                  VARCHAR(30)  NOT NULL,
    processing_stage                VARCHAR(30)  NOT NULL,
    payment_request_id              BIGINT       NOT NULL,
    idempotency_key                 UUID         NOT NULL,
    order_ref                       VARCHAR(100) NULL,
    correlation_id                  VARCHAR(100) NULL,
    evaluation_attempt_no           INT          NOT NULL,
    disposition                     VARCHAR(40)  NOT NULL,
    reason_code                     VARCHAR(100) NOT NULL,
    analyzed_at                     DATETIME(6)  NOT NULL,
    PRIMARY KEY (analysis_id),
    CONSTRAINT uk_fds_failure_analyses_report UNIQUE (failure_report_id),
    CONSTRAINT chk_fds_failure_analyses_positive CHECK (
        payment_id > 0 AND merchant_id > 0 AND requested_amount > 0 AND payment_request_id > 0
            AND evaluation_attempt_no > 0
    )
);
