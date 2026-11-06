-- create_payment_order_contacts
-- 생성: 2026-11-06 22:25:38 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 #602 (ADR-0082): 가맹점이 결제 준비 요청에 보낸 주문자 연락처. 사용자 승인으로 저장소 ERD · ADR 선행 반영, Notion 후속.
-- 이름 · 전화는 AES-GCM 암호문만 둔다. 전화는 원문 없이 같은 번호인지 대조하도록 HMAC 지문도 둔다.
-- 픽업자 원문은 저장하지 않고 "주문자와 다름" 여부만 남긴다 (11/03 팀 회의). request_digest는 같은 요청 키 재요청의 연락처 비교용 HMAC이다.
-- 세션 1건당 1행이라 PK는 session_id(같은 P5 표 payment_sessions의 FK)다. merchant_id는 암호화 AAD에 쓰며 P1 merchants의 논리 참조다.
CREATE TABLE payment_order_contacts
(
    session_id         BIGINT       NOT NULL,
    merchant_id        BIGINT       NOT NULL,
    orderer_name_enc   VARCHAR(512) NULL,
    orderer_phone_enc  VARCHAR(512) NULL,
    orderer_phone_hmac VARCHAR(64)  NULL,
    pickup_differs     BOOLEAN      NULL,
    request_digest     VARCHAR(64)  NOT NULL,
    key_id             VARCHAR(10)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT fk_payment_order_contacts_session FOREIGN KEY (session_id) REFERENCES payment_sessions (session_id),
    CONSTRAINT ck_payment_order_contacts_orderer CHECK (orderer_name_enc IS NOT NULL OR orderer_phone_enc IS NOT NULL),
    CONSTRAINT ck_payment_order_contacts_phone CHECK (
        (orderer_phone_enc IS NULL AND orderer_phone_hmac IS NULL)
        OR (orderer_phone_enc IS NOT NULL AND orderer_phone_hmac IS NOT NULL)
    ),
    INDEX idx_payment_order_contacts_phone (orderer_phone_hmac)
);
