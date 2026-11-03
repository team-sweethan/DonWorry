-- create_order_fulfillments
-- 생성: 2026-10-27 09:27:31 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- P6 로컬 2차 초안. 현재 CakeShop 단일 연동이며 주문번호 전역 유일성을 전제로 한다.
CREATE TABLE order_fulfillments
(
    fulfillment_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NULL,
    order_id VARCHAR(100) NOT NULL,
    scheduled_fulfillment_at DATETIME(6) NULL,
    fulfilled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (fulfillment_id),
    CONSTRAINT uk_order_fulfillments_order UNIQUE (order_id),
    CONSTRAINT chk_order_fulfillments_merchant CHECK (merchant_id IS NULL OR merchant_id > 0),
    CONSTRAINT chk_order_fulfillments_order CHECK (CHAR_LENGTH(TRIM(order_id)) > 0)
);
