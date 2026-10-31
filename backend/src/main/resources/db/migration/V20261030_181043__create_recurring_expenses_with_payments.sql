-- create_recurring_expenses_with_payments
-- 생성: 2026-10-30 18:10:43 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


CREATE TABLE recurring_expenses (
 recurring_expense_id BIGINT NOT NULL AUTO_INCREMENT,
 merchant_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 amount BIGINT NOT NULL,
 category VARCHAR(30) NOT NULL,
 memo VARCHAR(500) NULL,
 frequency VARCHAR(30) NOT NULL,
 start_date DATE NOT NULL,
 end_date DATE NULL,
 stopped_from DATE NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (recurring_expense_id),
 CONSTRAINT chk_recurring_expenses_merchant CHECK (merchant_id > 0),
 CONSTRAINT chk_recurring_expenses_amount CHECK (amount > 0 AND amount <= 9007199254740991),
 CONSTRAINT chk_recurring_expenses_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
 CONSTRAINT chk_recurring_expenses_category CHECK (category IN ('MATERIAL','RENT','PAYROLL','UTILITIES','OTHER')),
 CONSTRAINT chk_recurring_expenses_frequency CHECK (frequency IN ('DAILY','WEEKLY','MONTHLY')),
 CONSTRAINT chk_recurring_expenses_dates CHECK (end_date IS NULL OR end_date >= start_date),
 INDEX idx_recurring_expenses_merchant_start (merchant_id, start_date)
);

CREATE TABLE recurring_expense_payments (
 payment_id BIGINT NOT NULL AUTO_INCREMENT,
 recurring_expense_id BIGINT NOT NULL,
 merchant_id BIGINT NOT NULL,
 occurrence_date DATE NOT NULL,
 title VARCHAR(100) NOT NULL,
 amount BIGINT NOT NULL,
 category VARCHAR(30) NOT NULL,
 memo VARCHAR(500) NULL,
 paid_at DATETIME(6) NOT NULL,
 PRIMARY KEY (payment_id),
 CONSTRAINT uk_recurring_payment_occurrence UNIQUE (recurring_expense_id, occurrence_date),
 CONSTRAINT fk_recurring_payment_rule FOREIGN KEY (recurring_expense_id) REFERENCES recurring_expenses(recurring_expense_id),
 CONSTRAINT chk_recurring_payment_merchant CHECK (merchant_id > 0),
 CONSTRAINT chk_recurring_payment_amount CHECK (amount > 0 AND amount <= 9007199254740991),
 INDEX idx_recurring_payment_merchant_paid (merchant_id, paid_at),
 INDEX idx_recurring_payment_merchant_date (merchant_id, occurrence_date)
);
