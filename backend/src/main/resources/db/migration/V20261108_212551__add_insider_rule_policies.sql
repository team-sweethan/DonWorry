-- add_insider_rule_policies
-- 생성: 2026-11-08 21:25:51 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- 내부자 규칙 켜짐 · 꺼짐 (#685, ADR-0082). 저장 이름은 InsiderRule enum 이름 그대로(insider_alerts.rule_code와 같음)
-- INS_05(높음 경보 중 내보내기 차단)는 넣지 않는다 — 줄이 없으면 끄기 · 켜기 주소가 404라 끌 방법이 없다
INSERT INTO fds_rule_policies (rule_code, active, updated_by_user_id, updated_at)
VALUES ('INS_01', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('INS_03', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('INS_06', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('INS_07', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('INS_08', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('INS_09', TRUE, NULL, UTC_TIMESTAMP(6));