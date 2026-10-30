-- add_application_shop_url
-- 생성: 2026-10-30 10:50:49 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- P1 #354 PR A: 신규 신청은 주소 필수, 기존 신청은 수집하지 않은 NULL을 유지한다.
ALTER TABLE merchant_applications ADD COLUMN shop_url VARCHAR(2048) NULL;
