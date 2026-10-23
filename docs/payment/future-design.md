# P5 후속 검토 — 1차 필수 계약 아님

2026-10-23 · #104. 아래는 미합의 확장안이다. P6에 이 구조에 맞춘 새 식별자·외부 결과값을 요구하지 않는다.

| 안 | 원래 목적 | 다시 검토할 조건 |
| --- | --- | --- |
| payment_requests | 원 요청·멱등 키·채택 평가 보존 | 현재 ERD로 최초 요청 재생·재시작 복구를 충족하지 못할 때 |
| payment_operations | AUTH/VOID 실행과 CAPTURE 중계 작업 관리 | 접수와 장시간 실행을 독립적으로 관리해야 할 때 |
| payment_histories | 실행·접수 변경 이력, 결과 버전 | 필요한 감사·복구 근거의 보존 방식을 합의할 때 |
| p5OperationId·별도 targetOperationId | P5/P6 작업 상관관계 | 기존 paymentId·captureId로 부족한 업무가 확정될 때 |
| receiptVersion·sourceVersion·attemptNo | 접수 변경·여러 실행 결과의 순서 | 시도별 결과가 독립적으로 도착하는 프로토콜을 채택할 때 |
| resultRef·UNKNOWN 대조 | 외부 실행 결과 추적·불명 상태 복구 | 실제 외부 카드사 등 DB와 공동 커밋할 수 없는 실행을 도입할 때 |

저장안의 이유·대안은 [ADR-0010](../decisions/0010-payment-operation-storage.md)에 보존한다. 이전 상세 필드안은 Git 기준 `ae28a7c:docs/payment/design.md`, 이전 확장 검증안 T13~T15는 같은 커밋의 `docs/payment/verification.md`에서 확인할 수 있다.

후속 분리는 기본 중복 방지·오류 기록·재전달을 면제하지 않는다. 현재 ERD로 1차 필수 동작을 충족하지 못하면 구현 전에 담당 ERD와 저장 방식을 합의한다. 보조 3개를 일괄 채택하지 않고 필요한 최소 변경을 따로 검토한다.
기존 PaymentRules의 승인 UNKNOWN 판단은 이미 코드에 있는 내부 규칙이며 이번 문서 변경으로 제거하지 않는다. P6 완료 계약에서 UNKNOWN을 요구하지 않는 것과 구분한다.
