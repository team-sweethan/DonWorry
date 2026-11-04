-- index_operator_access_denial_retention
-- 생성: 2026-11-04 19:50:51 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- 사용자 조건 없는 보관 배치가 만료 시각 범위와 처리 순서를 인덱스로 찾는다.
CREATE INDEX idx_operator_access_denials_retention
    ON operator_access_denials (occurred_at, denial_id);
