-- add_ins10_rule_policy
-- 생성: 2026-11-10 11:32:33 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- SEC-INS-10 로그인 공격 중 처음 보는 브라우저 로그인 (#770, ADR-0082) 켜짐 · 꺼짐 줄. 다른 내부자 규칙처럼 켜진 채로 시작한다
INSERT INTO fds_rule_policies (rule_code, active, updated_by_user_id, updated_at)
VALUES ('INS_10', TRUE, NULL, UTC_TIMESTAMP(6));
