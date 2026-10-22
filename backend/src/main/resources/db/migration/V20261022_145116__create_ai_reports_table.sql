-- create_ai_reports_table
-- 생성: 2026-10-22 14:51:16 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P4 investigation/report · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)

-- Case별 AI 조사 보고서 (버전별로 쌓고 덮어쓰지 않는다)
CREATE TABLE ai_reports
(
    report_id        BIGINT      NOT NULL AUTO_INCREMENT,
    case_id          BIGINT      NOT NULL,                -- P3 security_cases 논리 참조 (같은 모듈이지만 FK 없음)
    report_version   INT         NOT NULL,
    evidence_version INT         NOT NULL,
    status           VARCHAR(20) NOT NULL,                -- PENDING / RUNNING / SUCCEEDED / FAILED
    report_body      JSON        NULL,                    -- 본문 · 증거 출처 · 조회 시각, 완료 전 NULL
    created_at       DATETIME(6) NOT NULL,                -- UTC
    completed_at     DATETIME(6) NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT uk_ai_reports_case_version UNIQUE (case_id, report_version)
);
