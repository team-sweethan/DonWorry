-- create_fds_tables
-- 생성: 2026-10-22 14:30:28 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 fds · 기준: Notion 팀페이지/ERD P2 (1차 MVP 일괄 생성, Issue #56)
-- ERD 양식과 다른 점: applied_score DEFAULT 0 (양식 "기본값 0 검토")

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

-- 평가 1건에 실행한 룰마다 한 행 (미적중도 NO_MATCH · 0점으로 저장)
CREATE TABLE fds_rule_hits
(
    hit_id             BIGINT       NOT NULL AUTO_INCREMENT,
    evaluation_id      BIGINT       NOT NULL,
    rule_code          VARCHAR(100) NOT NULL,             -- 예: HIGH_AMOUNT
    outcome            VARCHAR(20)  NOT NULL,             -- MATCH / NO_MATCH / UNAVAILABLE
    applied_score      INT          NOT NULL DEFAULT 0,
    rule_name_snapshot VARCHAR(200) NOT NULL,
    threshold_snapshot JSON         NULL,
    observed_value     JSON         NULL,
    evidence_refs      JSON         NULL,
    window_start       DATETIME(6)  NULL,                 -- 단건 룰이면 NULL
    window_end         DATETIME(6)  NULL,
    PRIMARY KEY (hit_id),
    CONSTRAINT fk_fds_rule_hits_evaluation FOREIGN KEY (evaluation_id) REFERENCES fds_evaluations (evaluation_id)
);
