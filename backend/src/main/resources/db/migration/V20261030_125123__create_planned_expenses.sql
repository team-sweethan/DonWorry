-- create_planned_expenses
-- 생성: 2026-10-30 12:51:23 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


CREATE TABLE planned_expenses (
    expense_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    expense_date DATE NOT NULL,
    amount BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    memo VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    PRIMARY KEY (expense_id),
    CONSTRAINT chk_planned_expenses_merchant CHECK (merchant_id > 0),
    CONSTRAINT chk_planned_expenses_amount CHECK (amount > 0 AND amount <= 9007199254740991),
    CONSTRAINT chk_planned_expenses_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT chk_planned_expenses_category CHECK (category IN ('MATERIAL','RENT','PAYROLL','UTILITIES','OTHER')),
    INDEX idx_planned_expenses_merchant_paid (merchant_id, paid_at, deleted_at),
    CONSTRAINT chk_planned_expenses_paid_deleted CHECK (paid_at IS NULL OR deleted_at IS NULL),
    INDEX idx_planned_expenses_merchant_date (merchant_id, expense_date, deleted_at)
);
