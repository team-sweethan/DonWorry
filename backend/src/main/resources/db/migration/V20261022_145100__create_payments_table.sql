-- create_payments_table
-- 생성: 2026-10-22 14:51:00 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 payment · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)

-- 결제 정보와 상태 (결제 상태의 정본)
CREATE TABLE payments
(
    payment_id        BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_id       BIGINT       NOT NULL,              -- P1 merchants 논리 참조 (FK 없음)
    customer_ref      VARCHAR(100) NOT NULL,
    order_ref         VARCHAR(100) NOT NULL,              -- CakeShop 주문번호
    amount            BIGINT       NOT NULL,              -- KRW 정수
    status            VARCHAR(30)  NOT NULL,              -- 최초 PENDING
    authorization_ref VARCHAR(100) NULL,                  -- 카드 승인 ID, 승인 전 NULL
    created_at        DATETIME(6)  NOT NULL,              -- UTC
    PRIMARY KEY (payment_id)
);
