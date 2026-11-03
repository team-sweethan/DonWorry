-- add_user_role_merchant_check
-- 생성: 2026-10-25 12:16:56 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- 수동 데이터가 규칙을 어기면 자동 보정하지 않고 적용을 실패시킨다.
ALTER TABLE platform_users
    ADD CONSTRAINT ck_platform_users_role_merchant CHECK (
        (role = 'OPERATOR' AND merchant_id IS NOT NULL)
        OR (role IN ('ADMIN', 'SECURITY_STAFF') AND merchant_id IS NULL)
    );
