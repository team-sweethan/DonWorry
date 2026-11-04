-- index_operator_access_denial_retention
-- 생성: 2026-11-04 19:50:51 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- 사용자 조건 없는 IP 정리 배치가 아직 IP가 남은 행만 찾는다.
-- 원본 행은 삭제하지 않으므로 IP를 비운 만료 행이 계속 쌓여도 검색 범위에 들어오지 않는다.
CREATE INDEX idx_operator_access_denials_retention
    ON operator_access_denials (client_ip, occurred_at);
