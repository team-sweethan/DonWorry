-- seed_demo_merchant_and_users
-- 생성: 2026-10-23 11:04:48 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 identity · 시연용 가맹점 · 사장님 · 보안 직원 계정 (비밀번호 둘 다 1234)
-- CakeShop 가맹점은 연동 확정 후 별도 migration으로 추가한다
-- pg_fee_rate 0.020000은 임시값 (P6 확인 전)
-- SECURITY_STAFF 화면은 role로 정하므로 user_permissions에는 사장님 권한만 넣는다

INSERT INTO merchants
    (merchant_code, display_name, status, pg_fee_rate, webhook_url, secret_reference, created_at)
VALUES
    ('DEMOSHOP', 'DemoShop', 'ACTIVE', 0.020000, NULL, NULL, UTC_TIMESTAMP(6));

INSERT INTO platform_users
    (login_id, password_hash, display_name, status, role, merchant_id, created_at)
VALUES
    ('boss', '$2a$10$gJU1ZSS8mHMm1lX1UK8peeJjvEFAlHj.8P.s.s/aHtnGuFYnxGU4u',
     'demo 사장님', 'ACTIVE', 'OPERATOR',
     (SELECT merchant_id FROM merchants WHERE merchant_code = 'DEMOSHOP'), UTC_TIMESTAMP(6)),
    ('staff_test', '$2a$10$gJU1ZSS8mHMm1lX1UK8peeJjvEFAlHj.8P.s.s/aHtnGuFYnxGU4u',
     'demo 보안직원', 'ACTIVE', 'SECURITY_STAFF', NULL, UTC_TIMESTAMP(6));

INSERT INTO user_permissions (user_id, permission_code)
SELECT user_id, 'FDS_VIEW' FROM platform_users WHERE login_id = 'boss'
UNION ALL
SELECT user_id, 'FINANCE_VIEW' FROM platform_users WHERE login_id = 'boss';
