-- add_fds_requested_amount
-- 생성: 2026-10-23 13:53:02 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 fds · 동일 평가 요청 ID의 재시도 검증을 위한 요청 금액 스냅샷
-- 기존 평가는 P5 결제 테이블의 금액으로 backfill하며, 물리 FK는 추가하지 않는다.
ALTER TABLE fds_evaluations
    ADD COLUMN requested_amount BIGINT NULL AFTER payment_requested_at;

UPDATE fds_evaluations evaluation
JOIN payments payment ON payment.payment_id = evaluation.payment_id
SET evaluation.requested_amount = payment.amount
WHERE evaluation.requested_amount IS NULL;

ALTER TABLE fds_evaluations
    MODIFY COLUMN requested_amount BIGINT NOT NULL;
