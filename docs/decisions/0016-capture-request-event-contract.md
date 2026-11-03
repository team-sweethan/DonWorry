# 0016. 1차 MVP 매입 요청 이벤트 계약

- **상태:** 채택 — P6 요청 계약. P5 발행 구현과 결과 계약은 별도 작업
- **날짜:** 2026-10-23
- **관련:** [ADR-0012](0012-payment-settlement-dependency.md)

## 배경

P6는 외부 금융기관 연동 없이 매입 완료와 정산 예정금을 하나의 트랜잭션으로 저장한다.
기존 P5 문서의 이벤트 필드안은 ERD 확정 전 제안이며, 이번 구현에서는 담당자가 확정한 네 필드를 사용한다.

## 결정

- `settlement.api.CaptureRequested`를 Builder가 있는 record로 정의한다.
- 필드는 `paymentId(Long)`, `merchantId(Long)`, `orderId(String)`, `amount(Long)`이다.
- P5의 `orderRef`는 이벤트의 `orderId`에 담는다. 금액은 원 단위다.
- P5는 업무 트랜잭션 안에서 `ApplicationEventPublisher`로 발행한다.
- P6는 `@ApplicationModuleListener`로 커밋 후 수신하고 기존 `CaptureService`에 위임한다.
- 동일 결제의 중복 매입은 기존 `captures.payment_id` UNIQUE와 서비스의 중복 처리로 막는다.
- 서비스 예외는 리스너 밖으로 전달한다. 자동 재발행 정책과 비동기 공통 설정은 이번에 추가하지 않는다.
- 접수 결과와 실행 결과의 계약은 이번 결정에 포함하지 않는다. P5와 별도 협의한다.

## 고려한 대안

| 대안 | 선택하지 않은 이유 |
| --- | --- |
| 요청 타입을 `payment.api`에 배치 | P6가 P5를 import하게 되어 ADR-0012의 의존 방향에 어긋난다 |
| eventId·작업 ID·승인 참조·별도 멱등 키 추가 | 현재 매입 처리에 필요하지 않으며 생성·저장·결과 대조 계약이 확정되지 않았다 |
| HTTP로 P5와 P6 연결 | 같은 앱 안 모듈은 Java 호출·이벤트를 사용한다 |

## 영향

- API: P5는 `settlement.api.CaptureRequested`를 import해 발행한다.
- DB: 기존 테이블과 migration을 유지한다. 이벤트 발행 기록은 기존 Modulith 저장소를 사용한다.
- 화면: 변경 없음.
- 테스트: 필드 전달·예외 전파, 커밋 전 미처리·커밋 후 저장·중복·롤백을 검증한다.
