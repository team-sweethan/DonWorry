-- create_order_fulfillment_items
-- 생성: 2026-10-29 14:36:36 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- 상품명은 주문 시점의 스냅샷이다. 같은 이름의 항목도 원본 배열 순서로 구분한다.
CREATE TABLE order_fulfillment_items
(
    fulfillment_item_id BIGINT NOT NULL AUTO_INCREMENT,
    fulfillment_id BIGINT NOT NULL,
    item_sequence INT NOT NULL,
    product_name VARCHAR(150) NOT NULL,
    quantity INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (fulfillment_item_id),
    CONSTRAINT uk_order_fulfillment_items_sequence UNIQUE (fulfillment_id, item_sequence),
    CONSTRAINT fk_order_fulfillment_items_fulfillment FOREIGN KEY (fulfillment_id)
        REFERENCES order_fulfillments (fulfillment_id),
    CONSTRAINT chk_order_fulfillment_items_sequence CHECK (item_sequence > 0),
    CONSTRAINT chk_order_fulfillment_items_name CHECK (CHAR_LENGTH(TRIM(product_name)) > 0),
    CONSTRAINT chk_order_fulfillment_items_quantity CHECK (quantity > 0)
);
