-- scope_order_fulfillments_by_merchant
-- 생성: 2026-10-29 15:56:34 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- 기존 merchant_id NULL 행은 소유자를 추정하지 않고 보존한다. 신규 요청은 인증된 ID로만 생성한다.
ALTER TABLE order_fulfillments
    DROP INDEX uk_order_fulfillments_order,
    ADD CONSTRAINT uk_order_fulfillments_merchant_order UNIQUE (merchant_id, order_id);
