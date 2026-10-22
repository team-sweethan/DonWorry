-- create_merchants_table
-- 생성: 2026-10-22 14:50:55 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 identity · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)
-- ERD 양식과 다른 점: 테이블명 복수화 (merchant → merchants)

-- 가맹점 (시드: CAKESHOP)
CREATE TABLE merchants
(
    merchant_id      BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_code    VARCHAR(40)   NOT NULL,
    display_name     VARCHAR(100)  NOT NULL,
    status           VARCHAR(20)   NOT NULL,              -- ACTIVE / INACTIVE
    pg_fee_rate      DECIMAL(9, 6) NOT NULL,
    webhook_url      VARCHAR(300)  NULL,                  -- CakeShop 결과 수신 URL
    secret_reference VARCHAR(200)  NULL,                  -- HMAC 키 참조, 원문은 환경변수
    created_at       DATETIME(6)   NOT NULL,              -- UTC
    PRIMARY KEY (merchant_id),
    CONSTRAINT uk_merchants_merchant_code UNIQUE (merchant_code)
);
