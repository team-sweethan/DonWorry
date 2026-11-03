-- create_merchant_settlement_locks
-- 생성: 2026-11-03 16:43:49 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P6 #488: 지급 날짜와 무관한 공통 트랜잭션 잠금. 가맹점은 P1 논리 참조다.
CREATE TABLE merchant_settlement_locks (
    lock_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(lock_id),
    CONSTRAINT uk_merchant_settlement_locks_merchant UNIQUE(merchant_id),
    CONSTRAINT chk_merchant_settlement_locks_merchant CHECK(merchant_id>0)
);
