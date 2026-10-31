# #381 고객 전액 취소 API 계약 초안

- 상태: **요청·응답 설계 초안, 구현·P1/P3/P6 상세 계약 확인 전**.
- 기준: `dev 7b0d4bb`, 2026-10-31. #153, #380~#383, [ADR-0056](../decisions/0056-cancellation-request-contract.md).
- 사용자 확정 정책: 전액만 지원, 정산 후에도 허용, 진행 중 일시 거절 후 같은 키 재요청, 정지 가맹점의 인증된 기존 결제 취소·조회 허용.
- 이 문서의 URI·필드·상태·오류 코드는 후속 구현을 위한 제안이다. Java enum·DB 컬럼·테이블 생성 승인이나 구현 사실이 아니다.

## 1. API와 인증

| 요청 | 역할 |
| --- | --- |
| `POST /api/payments/{paymentId}/cancellations` | 취소 요청 또는 동일 요청 재전송 |
| `GET /api/payment-cancellation-requests/{requestKey}` | 인증 가맹점의 해당 요청 기록·결과 조회 |
| 기존 `GET /api/payments/{paymentId}` | 현재 결제 상태 조회. 취소를 실행하지 않음 |

P1 HMAC으로 인증된 merchantId만 사용한다. 본문·쿼리로 merchantId를 받지 않는다. 모든 요청은 인증 후 결제 소유 범위를 확인한다. 없는 결제/요청과 타 가맹점의 결제/요청은 같은 404로 응답한다. 다른 가맹점에서 같은 요청 키를 사용해도 독립적이다.

정지 가맹점도 유효한 키로 기존 결제 취소·조회를 허용한다. 신규 결제 차단은 유지한다. 폐기된 키·서명 오류는 기존 인증 오류로 거절한다. P1의 HMAC 경로·메서드 목록과 CSRF 적용 범위에 새 경로를 명시적으로 연결해야 하며, 기존 모든 POST를 허용하도록 바꾸지 않는다. 인증·권한은 재요청과 GET에도 적용한다.

## 2. 요청

헤더 `Idempotency-Key`는 필수 UUID다. 기존 결제 요청과 같은 UUID 형식 검증을 사용하고 UUID 값으로 비교한다. 인증 nonce와 별개이며 재요청마다 새 nonce·서명으로 호출한다.

```json
{
  "reason": "고객 요청으로 주문 취소"
}
```

- paymentId는 양수 Long. reason은 필수 문자열, 공백만 있는 값·양끝 공백은 거절하고 Unicode 코드 포인트 기준 1~200자로 제안한다. 의미를 자동 변경하거나 사유를 자동 정규화하지 않는다.
- 금액은 입력받지 않고 저장된 결제 전액을 사용한다. amount·merchantId·decisionId 등 정의하지 않은 본문 필드는 400으로 거절한다. 기존 결제 API의 JSON 설정을 전역으로 바꾸지 않는다.
- 요청 키 비교 범위는 `(인증 merchantId, 요청 키)`이며 최초 내용 `(paymentId, reason)`을 고정한다. 결제 요청의 키와 취소 요청의 키는 서로 다른 업무 영역이다.
- 인증·형식·소유 확인에 실패한 요청은 취소 키를 예약하지 않는다. 올바른 요청의 키·내용을 영속 기록한 뒤 업무 판단 결과를 반환한다. 기록 실패는 5xx이며 키 보존을 보장했다고 응답하지 않는다.
- reason·인증 키·원문 요청 키는 로그에 남기지 않는다. 외부 조회는 아래 최소 응답을 사용하고 FDS 정보·승인 참조·직원 판정 ID·원시 내부 오류를 노출하지 않는다.

## 3. 요청 기록과 금융 실행 구분

`requestKey`는 접수 전 일시 거절도 조회할 수 있는 요청 식별자다. `cancellationId`는 실제로 접수된 금융 취소 작업의 식별자이며 접수 전에는 null이다. 단순히 요청 기록이 있다는 이유로 취소가 예약되거나 자동 실행되지 않는다.

| requestStatus 제안 | 의미 | 동일 키 POST |
| --- | --- | --- |
| DEFERRED | 진행 중이어서 실행 미접수 | 현재 조건을 다시 확인. 가능해지면 접수 |
| REVIEW_REQUIRED | 오류 복구·기록 확인 필요, 실행 미접수 | 복구 후 재요청 시 조건 재확인 |
| REJECTED | 취소 대상 없음 등 확정된 접수 거절 | 같은 결과 반환 |
| PROCESSING | 취소 작업 접수, 결과 대기 | 기존 작업 반환. 추가 실행 없음 |
| UNKNOWN | 접수된 작업의 금융 결과 미확정 | 기존 작업 조회·복구 안내. 새 실행 없음 |
| SUCCEEDED | 이번 요청에 연결된 취소 성공 확정 | 저장된 성공 결과 반환 |
| FAILED | 접수된 작업의 업무 실패 확정 | 저장된 실패 반환. 자동 재실행 없음 |
| ALREADY_CANCELED | 다른 요청 또는 직원 조치로 이미 취소 완료 | 기존 확정 사실 반환. 새 금융 실행 없음 |

접수 전 상태와 접수 후 상태를 구분하기 위한 외부 표현이며 DB 상태 설계는 후속이다. DEFERRED/REVIEW_REQUIRED의 갱신은 명시적 POST에서만 수행한다. GET은 현재 결제 상태를 읽을 수 있지만 요청 상태 재판정·취소 접수·이벤트 재전달을 실행하지 않는다.

## 4. 정상 응답 초안

```json
{
  "requestKey": "ad8fbf20-0f88-4b68-b878-7dd279a76a5b",
  "paymentId": 101,
  "requestStatus": "SUCCEEDED",
  "cancellationId": 501,
  "operation": "VOID",
  "amount": 25000,
  "requestedAt": "2026-10-31T09:00:00Z",
  "updatedAt": "2026-10-31T09:01:00Z",
  "completedAt": "2026-10-31T09:01:00Z",
  "code": null,
  "nextAction": "NONE"
}
```

- operation: 실제 접수 시 결정한 VOID 또는 REFUND. 접수 전 null. 고객이 지정하지 않는다. amount는 서버의 원 단위 전액이며 P6 정산 차감액과 다르다.
- requestedAt: 최초 요청 기록 시각. updatedAt: 요청 기록/연결된 결과가 실제 변경된 시각. 단순 GET·중복 POST로 갱신하지 않는다. completedAt: 실제 금융 결과 확정 시각. 접수 전·처리 중·미확정에는 null.
- ALREADY_CANCELED의 cancellationId는 기존 고객 취소 작업이 확인되는 경우 그 ID다. 직원 VOID만 존재하면 null이며 직원 actionId를 취소 ID로 둔갑시키지 않는다. operation·completedAt도 확인한 사실만 반환하며 알 수 없으면 null이다. 새 요청 시각을 과거 취소 완료 시각으로 사용하지 않는다.
- nextAction 제안: NONE / QUERY_PAYMENT_THEN_RESUBMIT / CONTACT_SUPPORT / QUERY_CANCELLATION / RETRY_SAME_REQUEST. 재시도 간격·횟수·자동 재시도를 약속하지 않는다.
- 기존 결제 조회의 paymentStatus를 환불 완료 증거로 사용하지 않는다. 신규 환불 후 결제 상태 표현은 #383의 별도 계약이며 이 문서에서 REFUNDED 등의 새 PaymentStatus를 확정하지 않는다.
- POST는 접수된 PROCESSING/UNKNOWN일 때 202, SUCCEEDED/ALREADY_CANCELED일 때 200이다. 확정된 거절·실패는 아래 오류 표를 따른다.
- GET은 해당 요청 기록이 있으면 상태와 관계없이 200으로 이 DTO를 반환한다. 기록이 없으면 404이며 원 요청이 처리 중·롤백·미도달 중 무엇인지 단정하지 않는다. 응답 유실 시 조회 후에도 새 키로 바꾸지 말고 기존 키·내용으로 확인한다.
- 모든 응답은 `Cache-Control: no-store`. 요청 기록 생성/조회가 가능한 POST 응답의 Location은 위 요청 키 GET 경로다. UUID 경로도 서버·프록시 접근 로그에 원문 키가 남지 않도록 마스킹 대상이다.

## 5. 오류 응답 초안

기존 Payment API처럼 ProblemDetail에 code를 추가한다. 보존된 요청이 있는 업무 거절/실패에는 requestKey·requestStatus·nextAction을 포함하여 동일 키 결과 조회로 연결한다. 5xx에서 저장 여부를 확신할 수 없으면 requestStatus를 추측하지 않는다.

| HTTP | code 제안 | 조건 / 다음 행동 |
| --- | --- | --- |
| 400 | INVALID_CANCELLATION_REQUEST | 키·ID·본문 형식 오류. 수정 후 요청 |
| 401 등 | 기존 P1 인증 코드 | 누락·폐기 키·서명/nonce 오류 등 기존 인증 계약 유지 |
| 404 | PAYMENT_NOT_FOUND | 결제 없음 또는 타 가맹점. 금융 실행 없음 |
| 404 | CANCELLATION_REQUEST_NOT_FOUND | 조회 키 없음 또는 타 가맹점 |
| 409 | IDEMPOTENCY_KEY_CONFLICT | 같은 키·다른 결제/사유. 기존 기록 변경 없음 |
| 409 | PAYMENT_IN_PROGRESS | DEFERRED. 결제 결과 조회 후 같은 키·내용 재요청 |
| 409 | PAYMENT_REVIEW_REQUIRED | REVIEW_REQUIRED. FDS 오류·P5/P6 불일치·불명확한 상태 등. 내부 원인 노출 없이 운영 확인 후 재요청 |
| 409 | PAYMENT_NOT_CANCELABLE | REJECTED. DENIED/DECLINED 등 취소할 금융 거래 없음. 취소 성공이 아님 |
| 409 | CANCELLATION_ALREADY_IN_PROGRESS | 다른 키의 취소 실행이 진행/미확정. 새 작업 생성 없음. 해당 가맹점이 기존 작업 결과를 확인하도록 기존 요청 조회 Location 제공 |
| 409 | CANCELLATION_PREVIOUSLY_FAILED | 다른 키의 확정 실패 작업이 있음. 신규 실행으로 우회하지 않고 운영 확인 |
| 422 | CANCELLATION_DECLINED | 접수 작업의 명시적 업무 실패 확정(FAILED). 같은 키는 실패 결과 유지 |
| 503 | CANCELLATION_UNAVAILABLE | DB·잠금·조회 장애. 결과 단정 금지. 기존 키로 조회/재확인 |
| 503 | CANCELLATION_NOT_READY | 상태별 실행부 또는 필수 연동 미준비. 성공·처리 중으로 위장하지 않음 |
| 500 | CANCELLATION_INTERNAL_ERROR | 예상 밖 오류. 결과 단정 금지. 같은 키 결과 확인 |

예: 진행 중 거절은 202가 아니다. 요청 기록이 있어도 실행 접수하지 않았으므로 409를 반환한다.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "결제 처리 결과를 확인한 뒤 같은 요청으로 다시 시도해주세요.",
  "code": "PAYMENT_IN_PROGRESS",
  "requestKey": "ad8fbf20-0f88-4b68-b878-7dd279a76a5b",
  "requestStatus": "DEFERRED",
  "nextAction": "QUERY_PAYMENT_THEN_RESUBMIT"
}
```

## 6. 중복·재요청 시나리오

1. 같은 키·같은 내용으로 진행 중 거절 → HELD 확정 → 같은 키 POST: 최신 조건을 재검사하여 한 번 접수. GET만으로 접수하지 않음.
2. 같은 키로 paymentId/사유 변경: 현재 결제 상태와 관계없이 409 충돌. 다른 가맹점의 기록은 대조·노출하지 않음.
3. 접수 뒤 응답 유실: GET 또는 같은 키 POST로 기존 작업 확인. HMAC은 새 nonce·서명 사용.
4. 다른 키로 같은 결제 요청: 기존 작업이 진행/미확정이면 추가 실행 차단, 성공이면 ALREADY_CANCELED, 실패이면 운영 확인. 아직 실행이 없는 DEFERRED 기록이 여러 개 있어도 최초 접수는 결제 기준으로 하나만 허용.
5. 먼저 거절됐던 키의 재요청 시 다른 요청이 이미 성공했다면 ALREADY_CANCELED로 연결. 기존 직원 VOID가 있어도 새로운 금융 실행은 하지 않음.
6. 상태 확인과 접수는 동일 결제 잠금 아래 직렬화한다. 승인 취소와 직원 매입의 경합 규칙은 #380을 유지한다. 키 충돌의 새 트랜잭션 재조회 패턴을 참고하되 주문 생성 서비스를 취소 접수에 호출하지 않는다.
7. 일시 거절의 키·내용 기록은 응답 전에 커밋돼야 한다. 409 예외로 전체 롤백하여 키가 사라지지 않도록 후속 저장 설계에서 검증한다. 접수 전 기록을 재시도 워커가 실행 대상으로 읽어서는 안 된다.

## 7. 후속 확인·검증

- P1: 새 HMAC 경로/메서드, 정지 가맹점 취소·조회 인가, 오류·헤더·키 마스킹 확인.
- P3: 기존 직원 취소 결과 확인과 고객 취소 경합의 Case·판정 반영. 직원 기록을 임의로 수정하지 않음.
- P6: #383·#233 성공 전달 필드·재전달과 정산 차감. 이 문서의 고객 응답은 P6 반영 완료나 실제 은행 입출금 완료를 보장하지 않음.
- 저장 설계: 요청 키와 금융 작업의 관계, 키 보존 기간·범위·UNIQUE·상태 전이·원자성, 직원 VOID 근거 조회, 미확정 복구 경계. 테이블/컬럼은 ERD 확인 후 별도 제안.
- 검증 계획: 인증·가맹점 격리·정지 가맹점, 키 충돌, 409 후 동일 키 접수, GET 부작용 없음, 응답 유실, 서로 다른 키 동시 접수, 기존 직원 취소, FAILED/UNKNOWN 재요청, 키 기록 롤백, 미연결 실행부의 성공 금지.
- 이번 검사는 Markdown 링크·공백과 예시 JSON 문법만 수행한다. API·보안·DB·금융 실행은 미구현·미검증이다.
