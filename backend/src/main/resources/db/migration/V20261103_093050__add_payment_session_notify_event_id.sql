-- add_payment_session_notify_event_id
-- 생성: 2026-11-03 09:30:50 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 결제 웹훅 재전송 한도 (ADR-0064 3절, #455). notify_event_id는 지금 재시도 중인 웹훅의 eventId
-- (evt_{paymentId}_{상태}_{A|N})다. 재시도 중 결제 상태가 바뀌어 보낼 eventId가 달라지면 실패 횟수와 간격을
-- 처음부터 다시 세서, 한도(약 24시간)가 상태마다 따로 적용되게 한다. 기존 행은 NULL로 두고 첫 실패 때 채운다.
ALTER TABLE payment_sessions ADD COLUMN notify_event_id VARCHAR(100) NULL AFTER notify_attempts;

