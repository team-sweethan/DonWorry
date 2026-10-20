# 0003. P5–P6 취소 계약의 의존 방향과 원자성

- **상태:** PROPOSED — 사용자 선택(질문 1-A), 팀 합의 대기, 구현 없음
- **날짜:** 2026-10-20
- **관련:** [ADR-0001](0001-module-dependency-direction.md), [P5 설계 출처](../payment/README.md), 사용자 최신 작업 요청, P5 Issue 미특정
- **영향 받는 담당:** P5 시은·P6 민정, P3 수민, 모듈 경계 담당 전원

## 배경

기존 취소 계약은 P5가 P6의 getCancellationEligibility/applyPaymentCancellation을 호출한다. P6는 매입을 위해 P5를 호출한다. 양측 api 타입을 서로 import하면 Modulith 순환이 된다. ADR-0001의 환불 마감 복제·이벤트 방안은 아직 제안이며 API-09/10 및 같은 트랜잭션 요구와 차이가 있다.

## 결정 제안

사용자가 선택한 방향에 따라 기존 메서드와 응답을 유지하는 `payment.api.CancellationSettlementPort`를 P5가 소유하고 P6가 구현하는 초안으로 정리한다. 컴파일 의존은 settlement → payment로 유지하고, 취소는 동일 DB·TM의 호출자 트랜잭션에 참여한다. **팀 채택 전이며 ADR-0001을 수정·대체하지 않는다.** 정적 의존은 정리되지만 실행 호출 방향은 양방향이므로 AGENTS의 규칙상 포트 허용 여부도 팀 합의 대기다. 기존 P6 API 소유 변경은 팀·정본 합의가 필요하다.

## 고려한 대안

| 대안 | 이번 권장안으로 선택하지 않은 이유 |
|---|---|
| 양방향 api 직접 호출 | 모듈 순환 금지 위반 |
| 새 상위 오케스트레이터 | 동기 원자성 가능하지만 모듈·진입점 변경 범위 큼 |
| 환불 마감 복제 + 비동기 이벤트 | 같은 커밋을 보장하지 않으며 실패 복구와 최신성 계약 필요 |
| 동기 이벤트 | 같은 커밋은 조건부 가능하지만 조회 응답·필수 listener 보장이 불명확해짐 |
| 순환 검사를 끄거나 공통 잡동사니 모듈 도입 | 기존 규칙 및 경계 검사 취지에 어긋남 |

## 영향

- API: API-09 응답 cancellable/refundDeadlineAt/settlementStatus/reasonCode, API-10 응답 paymentId/settlementStatus 유지. applied 필드 없음. 타입 소유만 제안 변경.
- DB: P5 테이블 추가 없음. P6 cancellationId 중복 저장 위치는 OPEN. 다른 모듈 FK 금지 유지.
- 화면: 고객 HELD 표현은 별도 합의. 이 ADR이 승인 결과 표시를 확정하지 않음.
- 테스트: verify(), 빈 의존 그래프, P6 실패 시 전체 롤백, 매입/취소 경합 필요.
- 문서: 승인 후 Notion 공통계약과 ADR-0001의 관계를 정리해야 함. 이번에는 기존 문서를 변경하지 않음.

## 검증

미검증 — 구현·DB 실험 없음. [트랜잭션 설계](../payment/06-transaction-locking.md)의 수용 조건으로 검증 예정. 현재 저장소에 업무 의존이 없다는 사실만 소스에서 확인했다. 이 초안은 기존 ADR 목록을 변경하지 않고 [P5 안내](../payment/README.md)에서 연결한다.
