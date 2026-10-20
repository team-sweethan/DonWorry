# P5 내부 Mock 카드사 계약

CONFIRMED(S6): 별도 카드사 HTTP 서버 없이 P5 내부에서 승인·거절·취소를 실행한다. 카드 결과와 payment 상태는 같은 DB 트랜잭션으로 커밋한다. [7개 테이블](02-erd.md) 외 새 테이블은 추가하지 않는다.
아래 상태 이름·오류 코드·번호 규격·테스트 카드 구분은 PROPOSED(Q16·Q17).

## 카드 종류와 상태

| 시연 카드 종류 | mock_card.status | 승인 동작 |
|---|---|---|
| 정상·잔여 한도 충분 | ACTIVE | 승인 가능 |
| 정상·잔여 한도 부족 | ACTIVE | LIMIT_EXCEEDED 업무 거절 |
| 도난 신고 | STOLEN | CARD_STOLEN 업무 거절 |
| 이용 정지 | SUSPENDED | CARD_SUSPENDED 업무 거절 |

시연 카드 개수·구체 한도·번호·초기 used_amount는 OPEN이며 시드 생성은 하지 않는다. 실제 PAN/CVV는 저장하지 않고 카드 선택에는 opaque token, 화면에는 masked_pan만 제공한다. token → fingerprint → mock_card 관계의 정확한 무결성은 [ERD](02-erd.md)를 따른다.

## 승인 알고리즘 (PROPOSED)

1. T1에서 payment 잠금, 멱등 응답 재생 여부 확인, REQUESTED 및 FDS 결과·입력 검증.
2. REJECT이면 카드 함수를 호출하지 않는다. ALLOW/HOLD만 승인 요청.
3. 선택 카드의 mock_card 행을 잠근다. client amount를 쓰지 않고 payment.amount를 사용한다.
4. request_id로 기존 원장이 있으면 payment_id/mock_card_id/request_type/amount가 모두 같은지 검사한다. 같으면 기존 결과, 다르면 충돌. 새 UUID를 발급해 실패 요청의 의미를 바꾸지 않는다.
5. `amount > 0`, 지원 통화, ACTIVE, `amount <= credit_limit - used_amount`를 검사한다. 덧셈 overflow를 피한다.
6. 성공: used_amount += amount, APPROVE/SUCCEEDED 원장, 승인번호, responded_at. ALLOW면 APPROVED, HOLD면 HELD와 approved_at을 저장한다.
7. 업무 거절: used_amount 불변, APPROVE/DECLINED 원장·decline_code 저장, payment DECLINED와 history를 저장하고 정상 커밋한다. 예외로 롤백해 거절 증거를 지우지 않는다.
8. SQL 오류 등 기술 실패는 P5 승인 트랜잭션을 전체 롤백하고 503 계열 오류를 반환한다. 별도로 커밋된 P2 실제 평가 시도 기록은 삭제하지 않는다. 장애를 업무 거절로 바꾸지 않는다.

## 취소 알고리즘 (PROPOSED)

1. T3는 P3 결정 저장과 같은 트랜잭션이며, T3/T4에서 payment 잠금, 성공 멱등 응답 확인, HELD/APPROVED 및 원승인 확인.
2. 필요한 P6 취소 허용 검사 후 원승인의 mock_card 행을 잠근다.
3. 원승인 금액 전체만 취소한다. 부분 취소는 1차 지원 범위에 포함하지 않는다(Q17).
4. 같은 CANCEL request_id는 같은 원장 결과를 재사용한다. 다른 키여도 이미 CANCELED이면 추가 차감하지 않는다. 성공한 기존 cancellationId를 재사용할지 상태 충돌로 응답할지 외부 계약은 Q04에서 확정한다. 권장안은 권한·본문을 검증한 후 기존 성공 결과 반환이다.
5. `used_amount >= canceledAmount` 검증 후 차감, CANCEL/SUCCEEDED 원장, canceled_at·history 저장, P6 반영, 응답 저장을 같은 커밋에 묶는다.
6. 승인 후 STOLEN/SUSPENDED가 되었더라도 원승인 취소는 허용하는 안을 권장한다. 도난 카드의 자금 해제를 막지 않기 위해서이며 Q16 합의가 필요하다.
7. 원장 불일치·음수 한도 위험·Mock 취소 실패는 성공 취소 기록 및 CANCELED를 남기지 않는다. 전체 롤백하고 운영 확인 대상으로 분류한다. 실패 시도 감사 영속화 정책은 OPEN이며 별도 트랜잭션을 몰래 추가하지 않는다.

## 원장과 식별자

- card_approval.request_type: APPROVE/CANCEL. result: SUCCEEDED/DECLINED 제안. 기술 실패는 롤백하므로 미완료 행을 정상 완료로 남기지 않는다. S5 nullable result/responded_at을 유지하되 로컬 정상 커밋 시에는 결과가 반드시 존재해야 한다.
- request_id는 UUID UNIQUE. P5가 안정적으로 만들거나 상위 불변 업무 ID에서 생성하는 규격은 Q03·Q04. APPROVE와 CANCEL은 다른 namespace로 구분한다.
- approval_no는 성공 APPROVE에만 UUID 기반 불투명 문자열을 발급하는 안. 외부 카드사의 실제 번호 형식을 흉내 낼 필요는 없다. 재호출은 새 번호를 발급하지 않는다.
- cancellationId는 성공한 CANCEL 행의 approval_id 활용안(PROPOSED). 카드 취소 처리 때 생성하여 원장에 영속화하고, 커밋된 응답을 idempotency_record에 보관한다. P6는 동일 ID를 중복 처리하지 않아야 한다. P6의 처리 키 저장 위치는 OPEN이며 존재하지 않는 컬럼을 확정하지 않는다.
- 취소 원장과 원승인은 payment_id로 연결한다. 1차 “결제당 성공 승인 1회·전체취소 1회” 제약 하에서 가능하다. 부분취소·재승인이 필요해지면 원승인 참조 확장을 별도로 결정한다.

## 한도 불변식과 검증

`0 <= used_amount <= credit_limit`. Mock 범위의 모든 초기 원장이 제공된다는 전제에서 used_amount는 성공 APPROVE 합계에서 성공 CANCEL 합계를 뺀 값과 같아야 한다. P6 매입/정산이나 P3 유지 시 used_amount를 변경하지 않는다. 정산 후 한도 복원 등 추가 카드 결제주기 정책은 이번 범위 밖이다.

미실행 테스트: 같은 카드로 다른 payment 동시 승인 시 한도 초과 방지, 동일 requestId 승인/취소 재전송, 다른 payload 재사용 거부, STOLEN/SUSPENDED 거절, HOLD 승인 후 취소 복구, P6 실패 시 Mock 변경 전체 롤백, 서로 다른 취소 키로 중복 차감 불가.

실제 외부 카드사는 DB 롤백으로 승인을 되돌릴 수 없다. 향후 외부 연동은 별도 상태·조회·보상·재처리 설계가 필요하다. 현재 로컬 Mock의 원자성을 외부 카드사에도 적용할 수 있다고 설명하지 않는다.

EXPIRED는 카드 승인이 없었던 결제의 종료이므로 Mock CANCEL을 호출하지 않는다. 토큰 재발급도 카드 승인·한도 변경을 수행하지 않는다. HELD 유지에서 최초 승인 시각을 보존하고, P6는 배치마다 APPROVED를 재조회하여 기매입을 제외한다(질문 7-A, 팀 합의 대기).
