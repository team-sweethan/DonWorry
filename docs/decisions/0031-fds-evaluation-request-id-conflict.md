# 0031. FDS 평가 요청 ID 재사용 충돌 처리

- **상태:** 제안 — 팀 합의 필요
- **날짜:** 2026-10-23
- **관련:** P5 FDS 평가 결과 결제 분기 PR #95
- **영향 받는 담당:** P2 정후, P5 시은

## 배경

P5가 동일 `evaluationRequestId`를 기술 재시도에 사용하면 P2는 기존 평가를 반환해야 한다. 그러나 다른 결제 요청이 같은 UUID를 잘못 사용해도 기존 평가를 반환하면, 첫 번째 결제의 판정이 두 번째 결제 분기에 사용될 수 있다.

기존 `fds_evaluations`에는 요청 금액 스냅샷이 없어 네 요청 입력값을 모두 비교할 수 없다.

## 결정

P2는 `fds_evaluations.requested_amount(BIGINT)`에 평가 요청 당시 금액을 저장한다.

동일 `evaluationRequestId`의 기존 평가를 반환하기 전에 `paymentId`, `customerRef`, `amount`, `paymentRequestedAt`을 비교한다. 네 값이 모두 같으면 재시도로 기존 평가를 반환하고, 하나라도 다르면 `FdsEvaluationRequestConflictException`을 P5에 전달한다.

비교는 `paymentRequestedAt`을 MariaDB `DATETIME(6)` 저장 정밀도인 마이크로초로 맞춰 수행한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| UUID만 비교하고 기존 평가 반환 | 다른 결제가 같은 UUID를 재사용했을 때 잘못된 결제 분기가 가능하다. |
| `paymentId`만 비교 | 같은 결제 ID라도 고객·금액·요청 시각이 다른 요청을 재시도로 오인할 수 있다. |
| P5 응답 DTO에 요청 입력값을 추가 | P5가 이미 요청값을 알고 있고, P2 저장 스냅샷 검증을 대신할 수 없다. |

## 영향

- API: P5가 `FdsEvaluationRequestConflictException`을 받아 결제 분기를 중단한다.
- DB: `fds_evaluations`에 `requested_amount BIGINT NOT NULL`을 추가한다. 기존 평가는 P5 `payments.amount`로 backfill하되, P2-P5 사이 물리 FK는 추가하지 않는다.
- 화면: 없음.
- 테스트: 정상 재시도와 두 기존 평가 반환 경로의 요청 충돌을 검증한다.
- 문서: P2 ERD의 요청 스냅샷 컬럼·재시도 제약을 갱신한다.

## 검증

미검증:

- 구현 후 `./gradlew check mariaDbTest`로 확인한다.
