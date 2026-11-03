-- create_case_histories_table
-- 생성: 2026-10-22 14:51:15 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 investigation · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)

-- Case 업무 이벤트 감사 이력 (INSERT 전용)
CREATE TABLE case_histories
(
    history_id BIGINT      NOT NULL AUTO_INCREMENT,
    case_id    BIGINT      NOT NULL,
    event_type VARCHAR(50) NOT NULL,                      -- CASE_CREATED / PAYMENT_LINKED / ... / CASE_CLOSED
    actor_type VARCHAR(20) NOT NULL,                      -- STAFF / SYSTEM
    actor_id   BIGINT      NULL,                          -- P1 platform_users 논리 참조, SYSTEM이면 NULL
    event_data JSON        NULL,
    created_at DATETIME(6) NOT NULL,                      -- UTC
    PRIMARY KEY (history_id),
    CONSTRAINT fk_case_histories_case FOREIGN KEY (case_id) REFERENCES security_cases (case_id)
);
