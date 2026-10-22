-- create_payment_decisions_table
-- 생성: 2026-10-22 14:51:11 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 investigation · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)

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
