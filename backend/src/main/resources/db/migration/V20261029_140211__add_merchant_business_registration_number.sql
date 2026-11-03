-- add_merchant_business_registration_number
-- 생성: 2026-10-29 14:02:11 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #315: 담당자 승인, 저장소 ERD·ADR-0043 선행 기록.
ALTER TABLE merchants
    ADD COLUMN business_registration_number VARCHAR(10) NULL,
    ADD INDEX idx_merchants_business_registration_number (business_registration_number);
