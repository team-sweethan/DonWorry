-- create_ai_switch_histories
-- 생성: 2026-11-07 10:16:35 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P4 AI 조사 긴급정지 스위치의 변경 기록 (#626, ADR-0083). 행을 추가만 하고, 마지막 행이 지금 상태다(행이 없으면 켜짐).
-- 바꾼 직원(P1 platform_users)은 논리 참조(물리 FK 없음). 이름은 그때 값을 남긴다 — 계정 이름이 바뀌어도 기록은 그대로.
CREATE TABLE ai_switch_histories
(
    switch_history_id  BIGINT       NOT NULL AUTO_INCREMENT,
    action             VARCHAR(20)  NOT NULL,
    reason             VARCHAR(500) NOT NULL,
    changed_by_user_id BIGINT       NOT NULL,
    changed_by_name    VARCHAR(100) NOT NULL,
    changed_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (switch_history_id),
    CONSTRAINT ck_ai_switch_histories_action CHECK (action IN ('STOPPED', 'RESUMED')),
    CONSTRAINT ck_ai_switch_histories_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    INDEX idx_ai_switch_histories_changed (changed_at)
);
