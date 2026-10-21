-- create_event_publication_table
-- 생성: 2026-10-21 21:54:12 UTC
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- Spring Modulith 이벤트 저장소(Event Publication Registry) 테이블
-- 커밋 후 이벤트(P5→P6 CAPTURE 요청, P5→P3 접수 결과 등)를 재전달하려고 기록한다
-- Flyway 전환 전에는 Modulith가 직접 만들었다 (schema-initialization.enabled=true)
--
-- 정의는 spring-modulith-events-jdbc 2.1.1의 schemas/v2/schema-mariadb.sql과 같다
-- * 테이블·컬럼 이름은 라이브러리 SQL이 대문자로 조회하므로 대문자를 유지한다 (Linux MariaDB는 대소문자 구분)
-- * 시각 컬럼은 업무 테이블 규칙(DATETIME(6))이 아니라 라이브러리 정의(TIMESTAMP(6))를 따른다
-- * Modulith 버전을 올려 스키마가 바뀌면 이 파일이 아니라 새 migration으로 맞춘다
-- * IF NOT EXISTS는 빼서, 같은 이름의 테이블이 이미 있으면 조용히 넘어가지 않고 실패하게 한다

CREATE TABLE EVENT_PUBLICATION
(
    ID                     VARCHAR(36)   NOT NULL,
    LISTENER_ID            VARCHAR(512)  NOT NULL,
    EVENT_TYPE             VARCHAR(512)  NOT NULL,
    SERIALIZED_EVENT       VARCHAR(4000) NOT NULL,
    PUBLICATION_DATE       TIMESTAMP(6)  NOT NULL,
    COMPLETION_DATE        TIMESTAMP(6)  NULL DEFAULT NULL,
    STATUS                 VARCHAR(20),
    COMPLETION_ATTEMPTS    INT,
    LAST_RESUBMISSION_DATE TIMESTAMP(6),
    PRIMARY KEY (ID),
    INDEX EVENT_PUBLICATION_BY_COMPLETION_DATE_IDX (COMPLETION_DATE)
);
