-- create_captures_table
-- 생성: 2026-10-22 14:51:18 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P6 settlement · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)

-- 결제별 매입 실행 기록 (결제별 최대 1건)
CREATE TABLE captures
(
    capture_id     BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id     BIGINT       NOT NULL,                 -- P5 payments 논리 참조 (FK 없음)
    merchant_id    BIGINT       NOT NULL,                 -- P1 merchants 논리 참조 (FK 없음)
    order_id       VARCHAR(100) NOT NULL,                 -- CakeShop 주문번호 (P5 order_ref와 같은 값)
    capture_amount BIGINT       NOT NULL,                 -- KRW 정수
    status         VARCHAR(30)  NOT NULL,                 -- PROCESSING / COMPLETED / FAILED
    captured_at    DATETIME(6)  NULL,                     -- UTC, 완료 전 NULL
    failure_reason TEXT         NULL,
    retry_count    INT          NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,                 -- UTC
    updated_at     DATETIME(6)  NOT NULL,                 -- UTC
    PRIMARY KEY (capture_id),
    CONSTRAINT uk_captures_payment UNIQUE (payment_id)
);
