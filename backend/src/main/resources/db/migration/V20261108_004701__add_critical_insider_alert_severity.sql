-- add_critical_insider_alert_severity
-- 생성: 2026-11-08 00:47:01 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 내부자 경보 등급에 CRITICAL(위험)을 더한다 (#651, ADR-0082 INS-05).
-- 열린 경보가 있는 보안 직원이 거래 내역을 내보내려 하면 INS-05 경보를 CRITICAL로 열고 계정을 정지한다.
-- 기존 행(CAUTION · HIGH)은 그대로 통과한다. 값을 넓히기만 해서 기존 코드와 함께 배포해도 된다
ALTER TABLE insider_alerts
    DROP CONSTRAINT ck_insider_alerts_severity,
    ADD CONSTRAINT ck_insider_alerts_severity CHECK (severity IN ('CAUTION', 'HIGH', 'CRITICAL'));
