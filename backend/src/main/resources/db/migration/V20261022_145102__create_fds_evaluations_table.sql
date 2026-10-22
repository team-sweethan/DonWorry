-- create_fds_evaluations_table
-- 생성: 2026-10-22 14:51:02 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 fds · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)

-- 결제별 FDS 평가 이력 (한 결제에 여러 건 가능)
CREATE TABLE fds_evaluations
(
    evaluation_id BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id    BIGINT       NOT NULL,                  -- P5 payments 논리 참조 (FK 없음)
    risk_score    INT          NULL,                      -- ERROR 시 NULL
    result        VARCHAR(20)  NOT NULL,                  -- ALLOW / REVIEW / DENY / ERROR
    evaluated_at  DATETIME(6)  NOT NULL,                  -- UTC
    error_code    VARCHAR(100) NULL,
    PRIMARY KEY (evaluation_id)
);
