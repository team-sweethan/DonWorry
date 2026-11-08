-- create_ai_insider_reports
-- 생성: 2026-11-08 13:47:20 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- P4 내부자 사건 해제 검토 보고서 (#675, ADR-0082 조각 9). 정지된 직원을 풀지 관리자가 판단할 때 볼 사실을 코드가 세어 남긴다.
-- 판(report_version)별로 쌓고 덮어쓰지 않는다 — 보통 사건 생성 때 1판, 차단(INS-05) 경보가 붙을 때 2판.
-- 같은 계기(trigger_type)는 사건당 한 판만 — 알림이 다시 와도 하나(uk_ai_insider_reports_trigger).
-- 만들 때 끝난 상태(SUCCEEDED · FAILED)로 저장하고 바꾸지 않는다. FAILED면 report_body에 오류 코드만 둔다.
-- 사건(P3 insider_cases) · 계기 경보(P2 insider_alerts)는 논리 참조(물리 FK 없음, AGENTS.md 4절).
-- report_body: 코드가 센 사실(facts) + 고정 문장 설명(narrative) + 한계(limits). 고객 번호 · IP · 원문은 담지 않는다.
-- FDS 사건 보고서(ai_reports)와는 따로 둔다 — 대상(결제 사건 / 직원 사건)과 만드는 방식(AI / 코드)이 다르다.
CREATE TABLE ai_insider_reports
(
    report_id        BIGINT      NOT NULL AUTO_INCREMENT,
    insider_case_id  BIGINT      NOT NULL,
    report_version   INT         NOT NULL,
    trigger_type     VARCHAR(30) NOT NULL,
    trigger_alert_id BIGINT      NOT NULL,
    status           VARCHAR(20) NOT NULL,
    report_body      JSON        NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT uk_ai_insider_reports_version UNIQUE (insider_case_id, report_version),
    CONSTRAINT uk_ai_insider_reports_trigger UNIQUE (insider_case_id, trigger_type),
    CONSTRAINT ck_ai_insider_reports_version CHECK (report_version >= 1),
    CONSTRAINT ck_ai_insider_reports_trigger CHECK (trigger_type IN ('CASE_OPENED', 'EXPORT_BLOCKED')),
    CONSTRAINT ck_ai_insider_reports_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
);
