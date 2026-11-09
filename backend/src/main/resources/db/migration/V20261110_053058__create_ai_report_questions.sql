-- create_ai_report_questions
-- 생성: 2026-11-10 05:30:58 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다



-- P4 보고서 뒤 사건 질문 · 답변 (#752, ADR-0086 5절). 질문 하나당 한 줄을 쌓고 덮어쓰거나 지우지 않는다.
-- 접수 때 RUNNING으로 저장 · 커밋한 뒤 ai-server를 부르고, 결과로 ANSWERED · DECLINED · FAILED 중 하나로 끝낸다 (끝난 줄은 바꾸지 않음).
-- report_id는 같은 담당(P4) ai_reports라 물리 FK. 사건(P3 security_cases) · 직원(P1 users)은 논리 참조(AGENTS.md 4절).
-- answer_body: 답 종류 · 문장 · 근거 사실 · 부른 도구 · 과정 기록, 실패면 error(code · reason). 끝나기 전 NULL.
-- 질문 횟수 상한(직원 · 보고서당 최근 24시간 30개)과 직전 질문 3개 조회가 (report_id, staff_id, asked_at) 인덱스를 쓴다.
CREATE TABLE ai_report_questions
(
    question_id             BIGINT       NOT NULL AUTO_INCREMENT,
    report_id               BIGINT       NOT NULL,
    case_id                 BIGINT       NOT NULL,
    staff_id                BIGINT       NOT NULL,
    question                VARCHAR(300) NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    answer_body             JSON         NULL,
    report_evidence_version INT          NOT NULL,
    asked_at                DATETIME(6)  NOT NULL,
    answered_at             DATETIME(6)  NULL,
    PRIMARY KEY (question_id),
    CONSTRAINT fk_ai_report_questions_report FOREIGN KEY (report_id) REFERENCES ai_reports (report_id),
    CONSTRAINT ck_ai_report_questions_status CHECK (status IN ('RUNNING', 'ANSWERED', 'DECLINED', 'FAILED')),
    INDEX idx_ai_report_questions_report_staff (report_id, staff_id, asked_at)
);
