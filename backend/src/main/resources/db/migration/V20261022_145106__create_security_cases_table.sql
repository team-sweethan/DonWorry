-- create_security_cases_table
-- 생성: 2026-10-22 14:51:06 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 investigation · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)
-- ERD 양식과 다른 점: evidence_version DEFAULT 1 (양식 "초기값 1 검토")

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
