-- create_user_permissions_table
-- 생성: 2026-10-22 14:50:59 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 identity · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)
-- ERD 양식과 다른 점: 테이블명 복수화, (user_id, permission_code) 복합 PK 대신 BIGINT 단일 PK + UNIQUE

-- 계정별 추가 권한
CREATE TABLE user_permissions
(
    permission_id   BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    permission_code VARCHAR(40) NOT NULL,                 -- FDS_VIEW / FINANCE_VIEW
    PRIMARY KEY (permission_id),
    CONSTRAINT uk_user_permissions_user_code UNIQUE (user_id, permission_code),
    CONSTRAINT fk_user_permissions_user FOREIGN KEY (user_id) REFERENCES platform_users (user_id)
);
