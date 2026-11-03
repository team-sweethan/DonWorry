-- create_action_handoffs_table
-- 생성: 2026-10-22 14:51:12 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 investigation · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)

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
