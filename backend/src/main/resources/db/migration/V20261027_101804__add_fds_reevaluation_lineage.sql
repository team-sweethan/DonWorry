-- add_fds_reevaluation_lineage
-- 생성: 2026-10-27 10:18:04 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 fds · 최초 평가와 오류 분석 뒤 생성한 새 평가를 연결한다.
ALTER TABLE fds_evaluations
    ADD COLUMN original_evaluation_request_id UUID NULL AFTER evaluation_request_id,
    ADD COLUMN reevaluation_retry_id UUID NULL AFTER original_evaluation_request_id,
    ADD CONSTRAINT uk_fds_evaluations_reevaluation_retry UNIQUE (reevaluation_retry_id),
    ADD INDEX idx_fds_evaluations_original_request (original_evaluation_request_id);
