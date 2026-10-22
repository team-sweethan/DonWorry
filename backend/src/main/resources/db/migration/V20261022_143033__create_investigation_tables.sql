-- create_investigation_tables
-- 생성: 2026-10-22 14:30:33 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 investigation · 기준: Notion 팀페이지/ERD P3 (1차 MVP 일괄 생성, Issue #56)
-- ERD 양식과 다른 점: security_cases.evidence_version DEFAULT 1 (양식 "초기값 1 검토")

-- 조사 Case
CREATE TABLE security_cases
(
    case_id          BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_id      BIGINT       NOT NULL,               -- P1 merchants 논리 참조 (FK 없음)
    customer_ref     VARCHAR(100) NOT NULL,
    case_type        VARCHAR(40)  NOT NULL,               -- PAYMENT_FRAUD
    status           VARCHAR(30)  NOT NULL,
    evidence_version INT          NOT NULL DEFAULT 1,     -- 결제 병합 시 증가
    created_at       DATETIME(6)  NOT NULL,               -- UTC
    closed_at        DATETIME(6)  NULL,
    PRIMARY KEY (case_id)
);

-- Case 병합 대상 검색 · 고객사 Case 목록
CREATE INDEX idx_security_cases_merge ON security_cases (merchant_id, customer_ref, case_type, status, created_at);
CREATE INDEX idx_security_cases_merchant_created ON security_cases (merchant_id, created_at);

-- Case와 결제 연결 (결제 한 건은 최대 한 Case)
CREATE TABLE case_payments
(
    case_payment_id BIGINT      NOT NULL AUTO_INCREMENT,
    case_id         BIGINT      NOT NULL,
    payment_id      BIGINT      NOT NULL,                 -- P5 payments 논리 참조 (FK 없음)
    linked_at       DATETIME(6) NOT NULL,                 -- UTC
    PRIMARY KEY (case_payment_id),
    CONSTRAINT uk_case_payments_payment UNIQUE (payment_id),
    CONSTRAINT fk_case_payments_case FOREIGN KEY (case_id) REFERENCES security_cases (case_id)
);

-- 결제별 직원 최종 판정 (1회)
CREATE TABLE payment_decisions
(
    decision_id      BIGINT      NOT NULL AUTO_INCREMENT,
    case_payment_id  BIGINT      NOT NULL,
    staff_id         BIGINT      NOT NULL,                -- P1 platform_users 논리 참조 (FK 없음)
    report_id        BIGINT      NULL,                    -- P4 ai_reports 논리 참조, 수동 판정이면 NULL
    decision         VARCHAR(20) NOT NULL,                -- ACCEPT / REJECT
    reason           TEXT        NOT NULL,
    evidence_version INT         NOT NULL,                -- 판정 시 확인한 Case 증거 버전
    decided_at       DATETIME(6) NOT NULL,                -- UTC
    PRIMARY KEY (decision_id),
    CONSTRAINT uk_payment_decisions_case_payment UNIQUE (case_payment_id),
    CONSTRAINT fk_payment_decisions_case_payment FOREIGN KEY (case_payment_id) REFERENCES case_payments (case_payment_id)
);

-- 금융 조치 인계와 영속 접수 확인 (판정별 최대 1건)
CREATE TABLE action_handoffs
(
    handoff_id          BIGINT       NOT NULL AUTO_INCREMENT,
    decision_id         BIGINT       NOT NULL,
    action_type         VARCHAR(20)  NOT NULL,            -- CAPTURE / VOID
    status              VARCHAR(20)  NOT NULL,            -- REQUESTED / ACCEPTED / FAILED
    idempotency_key     UUID         NOT NULL,            -- 재시도 시 같은 값 유지
    target_operation_id BIGINT       NULL,                -- 담당 모듈 작업 ID, 접수 전 NULL
    requested_at        DATETIME(6)  NOT NULL,            -- UTC, 재시도해도 유지
    last_attempted_at   DATETIME(6)  NOT NULL,            -- UTC, 재시도 시 갱신
    accepted_at         DATETIME(6)  NULL,
    last_error_code     VARCHAR(100) NULL,
    PRIMARY KEY (handoff_id),
    CONSTRAINT uk_action_handoffs_decision UNIQUE (decision_id),
    CONSTRAINT uk_action_handoffs_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_action_handoffs_decision FOREIGN KEY (decision_id) REFERENCES payment_decisions (decision_id)
);

-- 인계 재시도 대상 조회
CREATE INDEX idx_action_handoffs_retry ON action_handoffs (status, last_attempted_at);

-- Case 업무 이벤트 감사 이력 (INSERT 전용)
CREATE TABLE case_histories
(
    history_id BIGINT      NOT NULL AUTO_INCREMENT,
    case_id    BIGINT      NOT NULL,
    event_type VARCHAR(50) NOT NULL,                      -- CASE_CREATED / PAYMENT_LINKED / ... / CASE_CLOSED
    actor_type VARCHAR(20) NOT NULL,                      -- STAFF / SYSTEM
    actor_id   BIGINT      NULL,                          -- P1 platform_users 논리 참조, SYSTEM이면 NULL
    event_data JSON        NULL,
    created_at DATETIME(6) NOT NULL,                      -- UTC
    PRIMARY KEY (history_id),
    CONSTRAINT fk_case_histories_case FOREIGN KEY (case_id) REFERENCES security_cases (case_id)
);
