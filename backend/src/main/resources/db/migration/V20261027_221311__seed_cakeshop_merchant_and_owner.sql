-- seed_cakeshop_merchant_and_owner
-- 생성: 2026-10-27 22:13:11 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #246 결정: CakeShop 연동 가맹점과 시연 사장님 계정을 함께 등록한다.
-- pg_fee_rate 0.020000은 P6 확인 전 임시값이다. 키 원문은 환경별 서버 환경변수로만 전달한다.
INSERT INTO merchants
    (merchant_code, display_name, status, pg_fee_rate, webhook_url, secret_reference, created_at)
VALUES
    ('CAKESHOP', 'CakeShop', 'ACTIVE', 0.020000, NULL,
     'DONWORRY_MERCHANT_HMAC_CAKESHOP', UTC_TIMESTAMP(6));

-- 기존 시연 계정과 같은 비밀번호 1234. 운영 시연 계정 제한은 #131 후속 범위를 따른다.
INSERT INTO platform_users
    (login_id, password_hash, display_name, status, role, merchant_id, created_at)
VALUES
    ('cakeshop_owner', '$2a$10$gJU1ZSS8mHMm1lX1UK8peeJjvEFAlHj.8P.s.s/aHtnGuFYnxGU4u',
     'CakeShop 사장님', 'ACTIVE', 'OPERATOR',
     (SELECT merchant_id FROM merchants WHERE merchant_code = 'CAKESHOP'), UTC_TIMESTAMP(6));

INSERT INTO user_permissions (user_id, permission_code)
SELECT user_id, 'FDS_VIEW' FROM platform_users WHERE login_id = 'cakeshop_owner'
UNION ALL
SELECT user_id, 'FINANCE_VIEW' FROM platform_users WHERE login_id = 'cakeshop_owner';

