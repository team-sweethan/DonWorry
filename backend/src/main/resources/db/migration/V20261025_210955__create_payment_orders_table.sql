-- create_payment_orders_table
-- 생성: 2026-10-25 21:09:55 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

CREATE TABLE payment_orders (
    payment_order_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    order_ref VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    customer_ref VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    amount BIGINT NOT NULL,
    order_created_at DATETIME(6) NOT NULL,
    payment_deadline_at DATETIME(6) NOT NULL,
    last_attempt_no BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (payment_order_id),
    CONSTRAINT uk_payment_orders_merchant_order UNIQUE (merchant_id, order_ref),
    CONSTRAINT uk_payment_orders_id_merchant UNIQUE (payment_order_id, merchant_id),
    CONSTRAINT chk_payment_orders_values CHECK (
        merchant_id > 0 AND amount > 0 AND last_attempt_no >= 0
        AND CHAR_LENGTH(TRIM(order_ref)) > 0 AND CHAR_LENGTH(TRIM(customer_ref)) > 0
        AND payment_deadline_at > order_created_at
    )
) DEFAULT CHARACTER SET utf8mb4;
