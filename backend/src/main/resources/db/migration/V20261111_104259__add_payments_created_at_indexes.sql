-- add_payments_created_at_indexes
-- 생성: 2026-11-11 10:42:59 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 payment · 보안 직원 거래 내역 최신순 조회 (#797)
-- 기간(created_at) 조건 + 결제 시각 최신순 정렬. 처음 화면(전체 가맹점)은 created_at,
-- 가맹점을 고른 화면은 (merchant_id, created_at)을 탄다. 하나만 두면 다른 쪽은 기간 안의 결제를 전부 훑는다
-- 칸 · 데이터는 바꾸지 않는다
ALTER TABLE payments
    ADD INDEX idx_payments_created (created_at),
    ADD INDEX idx_payments_merchant_created (merchant_id, created_at);
