-- add_fds_evaluation_request_fields
-- 생성: 2026-10-22 17:41:40 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5가 보낸 평가 요청을 보존한다. payment_requested_at은 반복 결제 룰의 시간 기준이다.
ALTER TABLE fds_evaluations
    ADD COLUMN evaluation_request_id UUID         NOT NULL AFTER evaluation_id,
    ADD COLUMN customer_ref          VARCHAR(100) NOT NULL AFTER payment_id,
    ADD COLUMN payment_requested_at DATETIME(6)  NOT NULL AFTER customer_ref,
    ADD CONSTRAINT uk_fds_evaluations_request_id UNIQUE (evaluation_request_id),
    ADD INDEX idx_fds_evaluations_customer_requested (customer_ref, payment_requested_at);
