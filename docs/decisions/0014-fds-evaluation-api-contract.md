# 0014. P5 → P2 FDS 평가 공개 계약

- **상태:** 제안
- **날짜:** 2026-10-22
- **관련:** ADR-0012, P5 C2, Notion FDS 정본
- **영향 받는 담당:** P2 정후, P5 시은

## 배경

P5는 결제 처리 중 P2의 위험 평가 결과를 즉시 받아야 한다. `payment → fds` 의존 방향과
동기 호출 창구 `FdsEvaluator`는 ADR-0012로 정해졌지만, 요청·응답의 필드와 오류 표현은 아직
코드 계약으로 고정되지 않았다.

## 결정

P2는 `fds.api`에 `FdsEvaluator`, `FdsEvaluationRequest`, `FdsEvaluationResponse`, `FdsResult`를
공개한다. P5는 `FdsEvaluator.evaluate(request)`를 동기로 호출한다.

- 요청 필수값은 `evaluationRequestId(UUID)`, `paymentId(Long)`, `merchantId(Long)`,
  `customerRef(String)`, `amount(long)`, `paymentRequestedAt(Instant)`이다.
- `merchantId`는 반복 결제 룰을 가맹점 경계 안에서 계산하기 위한 요청 스냅샷이다. P5가
  저장된 결제의 값을 전달하며, P2는 payment 모듈 저장소를 조회하지 않는다.
- `paymentRequestedAt`은 반복 결제 룰의 시간 구간 기준이고, P2의 평가 완료 시각과 구분한다.
- 응답은 `evaluationRequestId(UUID)`, `evaluationId(Long)`, `result(FdsResult)`,
  `riskScore(Integer)`, `evaluatedAt(Instant)`, `errorCode(String)`를 반환한다.
- `ERROR`면 `riskScore`는 `null`, `errorCode`는 비어 있지 않아야 한다. 그 외 결과에서는
  `riskScore`가 0 이상이고 `errorCode`는 `null`이어야 한다.
- 요청 형식이 잘못되면 호출 계약 위반으로 `IllegalArgumentException`을 던진다. P2 평가 처리 중
  기록 가능한 실패는 `ERROR` 응답으로 반환한다. P2 자체에 연결할 수 없는 실패는 호출 예외로
  P5가 처리한다.

`evaluationRequestId`의 재시작 이후 멱등 보장과 오류 코드 목록은 평가 저장 구현 시 별도 합의한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| `paymentId`만 요청 식별자로 사용 | 한 결제의 재평가 이력과 통신 재시도를 구분할 수 없다. |
| `evaluatedAt`을 10분 룰 기준으로 사용 | 큐 대기·재시도 시간에 따라 같은 결제 묶음의 결과가 달라진다. |
| `ruleHits`를 P5 동기 응답에 포함 | P5의 결제 분기에 필요하지 않은 근거까지 공개 계약에 고정한다. |
| P2가 `payments`를 조회해 가맹점을 결정 | FDS가 P5 내부 저장소에 직접 의존해 모듈 경계를 넘는다. |

## 영향

- API: P5는 `fds.api`만 import해 요청을 만들고 결과별 결제 상태 처리를 구현할 수 있다.
- DB: `fds_evaluations`에 `evaluation_request_id(UUID)`, `merchant_id(BIGINT)`,
  `customer_ref(VARCHAR(100))`, `payment_requested_at(DATETIME(6))`을 저장한다.
  `evaluation_request_id`는 유니크로 보장하고, 반복 결제 조회는
  `(merchant_id, customer_ref, payment_requested_at)` 인덱스를 사용한다. `merchant_id` 추가 전의
  기존 FDS 평가는 가맹점 1로 백필한다. P5 `payments`를 JOIN하지 않으며, 이 과거 이력 귀속은
  새 NOT NULL 컬럼 전환을 위한 호환 처리다. Notion P2 담당 ERD의 필드 반영은 PR 병합 전 확인한다.
- 화면: P2 경보 목록·상세 조회에 가맹점 ID를 함께 제공한다. 룰 근거는 P2 경보 상세 조회 계약에서 제공한다.
- 테스트: DTO 필수값과 `ERROR`/정상 응답 불변식을 단위 테스트로 확인한다.
- 문서: P5 C2의 세부 계약 후보가 생긴다. 팀 합의 뒤 이 ADR 상태를 채택으로 변경한다.

## 검증

- `./gradlew spotlessApply` — 미실행
- `./gradlew check` — 미실행
