-- add_fds_blocklist_identifiers
-- 생성: 2026-10-28 20:18:21 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

ALTER TABLE fds_evaluations
    ADD COLUMN ip_address VARCHAR(45) NULL AFTER ip_country_code,
    ADD COLUMN buyer_email VARCHAR(254) NULL AFTER ip_address,
    ADD COLUMN card_display_label VARCHAR(50) NULL AFTER card_token;

ALTER TABLE fds_blocklist_entries
    ADD COLUMN display_label VARCHAR(50) NULL AFTER identifier_value;
