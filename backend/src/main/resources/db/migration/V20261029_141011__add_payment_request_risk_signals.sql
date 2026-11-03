-- add_payment_request_risk_signals
-- 생성: 2026-10-29 14:10:11 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- 가맹점이 결제 시도마다 보내는 FDS 식별자 (#325). 모두 선택값이라 NULL을 허용한다.
-- 길이는 P2 fds_evaluations 컬럼과 같다. 카드 원문은 저장하지 않는다 (토큰 · 마스킹 표시값만).
ALTER TABLE payment_requests
    ADD COLUMN device_id VARCHAR(100) NULL AFTER evaluation_request_id,
    ADD COLUMN card_token VARCHAR(255) NULL AFTER device_id,
    ADD COLUMN card_display_label VARCHAR(50) NULL AFTER card_token,
    ADD COLUMN ip_country_code CHAR(2) NULL AFTER card_display_label,
    ADD COLUMN ip_address VARCHAR(45) NULL AFTER ip_country_code,
    ADD COLUMN buyer_email VARCHAR(254) NULL AFTER ip_address;
