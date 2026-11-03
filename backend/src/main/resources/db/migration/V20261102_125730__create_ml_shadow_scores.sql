-- create_ml_shadow_scores
-- 생성: 2026-11-02 12:57:30 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- P4 investigation/report · 기준: docs/investigation/375-fds-ml-shadow-design.md 7절 (#412)

-- 커밋된 FDS 평가를 ai-server RF로 따로 채점한 기록 (결제 판정에 쓰지 않고, 지우지 않는다)
CREATE TABLE ml_shadow_scores
(
    shadow_score_id   BIGINT        NOT NULL AUTO_INCREMENT,
    payment_id        BIGINT        NOT NULL,             -- P5 payments 논리 참조
    evaluation_id     BIGINT        NOT NULL,             -- P2 fds_evaluations 논리 참조 (결제의 첫 비오류 평가), 커서 기준
    model_version     VARCHAR(50)   NOT NULL,             -- 예: rf-v1
    status            VARCHAR(20)   NOT NULL,             -- SUCCEEDED / FAILED / SKIPPED
    rule_result       VARCHAR(20)   NOT NULL,             -- 평가 당시 Rule 결과 ALLOW / REVIEW / DENY
    rule_risk_score   INT           NULL,                 -- 평가 당시 Rule 점수
    fraud_probability DECIMAL(9, 6) NULL,                 -- ML 확률 0~1, 성공일 때만
    threshold         DECIMAL(9, 6) NULL,                 -- HIGH / LOW 기준값
    ml_risk_level     VARCHAR(10)   NULL,                 -- HIGH / LOW (ai-server 응답 그대로)
    features          JSON          NULL,                 -- 모델 입력 13개
    latency_ms        INT           NULL,
    failure_code      VARCHAR(100)  NULL,
    attempt_count     INT           NOT NULL,             -- 호출 시도 수, SKIPPED는 0
    scored_at         DATETIME(6)   NULL,                 -- UTC, 성공 시각
    created_at        DATETIME(6)   NOT NULL,             -- UTC
    updated_at        DATETIME(6)   NOT NULL,             -- UTC
    PRIMARY KEY (shadow_score_id),
    CONSTRAINT uk_ml_shadow_scores_payment_model UNIQUE (payment_id, model_version),
    INDEX idx_ml_shadow_scores_model_evaluation (model_version, evaluation_id)
);
