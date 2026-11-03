-- create_case_payments_table
-- 생성: 2026-10-22 14:51:08 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 investigation · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)

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
