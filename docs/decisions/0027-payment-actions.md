# 0027. P5 직원 금융 조치 단일 저장과 완료 기반 인계

- **상태:** 제안 — #141 저장소 상세 ERD 기준 구현 승인, #158 P3 계약 확인 반영. CAPTURE 확정 실패는 P5·P6 후속 협의, 팀 채택 상태는 별도
- **날짜:** 2026-10-25
- **관련:** Issue #141, [Issue #158](https://github.com/team-sweethan/DonWorry/issues/158), [ADR-0010](0010-payment-operation-storage.md), [ADR-0012](0012-payment-settlement-dependency.md), [ADR-0018](0018-payment-mvp-capture-completion.md) (이 ADR로 대체)
- **영향 받는 담당:** P5 시은 · P3 수민. P6 공개 이벤트 변경 없음

## 배경

`078a5c8`의 P5 payments는 결제 상태·승인 참조만 저장한다. P3는 판정·인계·멱등 키를 저장하지만 실제 금융 요청은 목업이다. 직원 ACCEPT를 비동기 매입으로 연결하려면 완료를 원래 판정에 연결하고, 대기 중 반대 조치 및 재시작 후 중복 실행을 막을 P5 저장 근거가 필요하다.

사용자는 요청 4필드·완료 기반 인계·정상 대기 구분을 확정해 P3에 전달했다고 알렸다. 이어 P5 소유 테이블 하나와 VOID 식별자로 P5 actionId를 쓰는 방향을 승인했다. 이후 사용자는 상세 스키마를 저장소 ERD·ADR 기준으로 구현하고 Notion은 후속 반영하도록 승인했다. #158에서 P3가 완료 기반 인계, CAPTURE/VOID 식별자와 시각 기준에 동의했다. 이 회신으로 해당 추가 P3 계약 확인을 갈음한다. 2026-10-26 사용자는 CAPTURE 확정 실패 처리를 P5·P6 후속 협의 대상으로 남기기로 결정했다.

## 결정

P5 소유 `payment_actions`에 직원 조치 한 건의 요청과 최종 결과를 함께 보존한다. 결제별 한 조치, 요청 키별 한 조치, 판정별 한 조치로 제한한다. 동일 요청의 기술 재시도는 같은 행을 사용하고 판정 정정·반대 조치 변경은 지원하지 않는다.

- 공개 Java 요청과 결과 이벤트는 payment.api에 둔다. 기존 의존 방향은 유지한다.
- 신규 CAPTURE 요청 저장과 P6 이벤트 발행을 원자적으로 커밋하고 PENDING을 반환한다. 실제 완료 후 CAPTURED·조치 SUCCEEDED·P3 결과 발행을 같은 트랜잭션에서 처리한다.
- VOID는 외부 부작용이 없는 모의 취소를 로컬 트랜잭션에서 실행한다. 성공한 경우만 VOIDED·SUCCEEDED·결과 발행을 함께 저장한다.
- P3 인계 ACCEPTED는 이번 경로에서 실제 성공 확인을 뜻한다. 성공 전에는 REQUESTED를 유지하므로 Case 종료도 완료를 기다린다. 정상 대기와 응답 미확인을 구분한다.
- CAPTURE targetOperationId는 P6 captureId, VOID는 P5 actionId를 사용한다. actionType과 함께 해석한다. P3 acceptedAt은 P3 반영 시각을 유지한다. 실제 완료 시각 completedAt은 P3의 case_histories.event_data에 함께 남기는 계약이다. P3 구현·검증과 구분한다.
- P5 내부 상태는 PENDING/SUCCEEDED/FAILED이며 업무 확정 실패만 FAILED다. 기술 예외를 영구 거절로 바꾸지 않는다. 상세 계약·동시성·복구는 [구현 설계](../payment/141-payment-actions.md), 컬럼은 [ERD 초안](../erd/README.md#141-p5-직원-조치-저장-설계-초안)을 따른다.

### 기존 ADR과의 관계

ADR-0010의 요청·금융 작업·이력 3개 테이블 전체를 채택하지 않는다. 이번에는 직원 판정 조치만 저장하는 하나의 테이블을 제안하며, 최초 결제 요청·AUTH 이력은 범위 밖이다.

이 ADR은 ADR-0018을 대체한다. CAPTURE 완료 기반 인계 제안을 구체화하되 acceptedAt=capturedAt 제안은 사용하지 않는다. VOID도 실제 모의 취소 완료 후 인계 확인으로 확장한다. ADR-0012의 의존 방향·타입 위치는 유지하고, 기존 접수/실행 분리 설명과 이번 완료 기반 정책의 차이를 명시한다. ADR-0018 본문은 이전 제안의 기록으로 보존하며, 대체 상태가 이 ADR의 팀 채택이나 P3 구현 완료를 뜻하지 않는다.

### #158 P3 처리 계약과 남은 협의

- 정상 PENDING은 요청 전달 성공이다. P3 인계는 REQUESTED, Case는 ACTION_PENDING으로 유지하고 결과 이벤트를 기다린다. 대기만으로 예외나 자동 재요청을 만들지 않는다.
- CAPTURE가 계속 PENDING이면 P3는 24시간 SLA 경고와 "금융 처리 대기 중" 표시를 제공하는 방침이다. 직원의 "인계 상태 다시 확인"은 같은 멱등키로 저장 결과를 다시 조회한다. 이 재조회가 P6 매입을 다시 발행하지는 않는다.
- VOID 확정 거절은 P3 인계 FAILED·Case ACTION_FAILED와 실패 코드 표시로 처리하는 방침이다. 저장된 실패는 같은 요청에 동일하게 반환하며 자동 재시도하지 않는다. 판정 정정은 후속 확장이다.
- 시간이 지났다는 이유만으로 P3가 FAILED 또는 CLOSED로 바꾸지 않는다. 요청 접수 단계의 거절과 접수된 조치의 확정 실패는 구분하며, 코드별 저장·이벤트 여부는 [직원 조치 설계 §2](../payment/141-payment-actions.md#2-공개-계약)를 따른다.
- P3는 멱등키로 인계를 찾고 paymentId·decisionId·actionType을 대조한다. 완료 상태는 늦은 응답으로 되돌리지 않고, 같은 결과는 중복 반영하지 않는다. 다른 targetOperationId의 성공은 충돌로 취급한다.
- 배포는 P3 운영 리스너 PaymentHeld·PaymentActionResult 연결을 먼저 확인하고 실제 발행 흐름을 활성화하는 순서로 맞춘다. 수신자 설치 전 결과는 자동 소급 전달되지 않으며, 같은 요청으로 저장 결과를 조회해 복구한다.
- **CAPTURE 확정 실패는 후속 협의 대상이다.** 현재 P6 공개 계약은 CaptureRequested·CaptureCompleted이며 CaptureFailed는 없다. P6 기술 예외는 P5 조치 PENDING과 미완료 전달 기록으로 남기고 REJECTED로 바꾸지 않는다. P5·P6가 확정 실패 기준·전달 계약·복구 방식과 도입 시점을 추후 협의한다. 2차 구현 확정으로 표시하지 않는다.
- P3 처리 방침은 #158의 회신 근거이며 이번 P5 문서 작업이 P3 화면·리스너 구현을 완료하지는 않는다. Notion 차이는 P3가 notion-sync Issue로 관리한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| payments에 요청·결과 컬럼을 모두 추가 | 직원 조치가 없는 ALLOW/최초 결제에도 별도 생명주기 필드가 섞인다 |
| ADR-0010의 세 테이블 전체 도입 | 이번 직원 조치 범위를 넘어 최초 요청·AUTH·범용 이력을 확대한다 |
| P3 action_handoffs 직접 조회 | 소유 경계와 의존 방향을 위반하며 P5의 실행 근거를 P3 내부에 의존시킨다 |
| 메모리 키 캐시 또는 이벤트 기록만 사용 | 재시작·이벤트 보존 정책과 무관하게 결과를 조회하고 반대 조치를 막을 업무 정본이 필요하다 |
| VOID 결과에 paymentId를 작업 ID로 사용 | 결제와 조치 식별 의미가 섞인다. 새 조치 PK를 사용하는 편이 추적에 명확하다 |
| 요청 발행 즉시 P3 ACCEPTED | 실제 실행이 실패하거나 대기 중이어도 Case가 종료될 수 있어 사용자 확정 방향과 다르다 |
| 모의 VOID를 별도 비동기 단계로 분리 | 외부 부작용이 없는 로컬 실행에 추가 이벤트·대기 상태가 생긴다. 실제 카드사 연동 때 재설계한다 |

## 영향

- API: PaymentActionRequester·Command·Receipt·Result 구현. P3 Gateway 매핑·PENDING 처리·최종 결과 멱등 수신은 P3 담당 후속 구현이다.
- DB: P5 payment_actions 신규 migration 구현, payments 컬럼은 유지. payments 물리 FK, P3 decision/P6 capture는 논리 참조. 사용자가 승인한 저장소 상세 ERD 기준으로 migration을 생성했다. Notion 동기화는 후속이다.
- 화면: P3 Case가 실제 완료까지 열려 있게 된다. 화면 수정은 이번 P5 범위 밖이다.
- 테스트: 트랜잭션 원자성·동시 충돌·중복·재전달·영속 결과 복구·기존 ALLOW 회귀가 필요하다.
- 문서: P5 설계·README, ERD에 P5 실행·결과 발행 구현과 P3 운영 연결 미구현을 구분한다. Notion 동기화는 후속이다.

## 검증

현재 코드·SQL과 P3 목업 및 인계 처리 흐름을 대조한 설계다. 4번 단계에서 migration·Entity·공개 계약·내부 요청 저장을 구현했다. 5번 단계에서 직원 CAPTURE 요청·완료·결과 발행을 연결했다. 6번 단계에서 모의 VOID·VOIDED·결과 발행도 연결했다. P3 운영 연동·Notion 동기화는 후속이다. 검사 결과와 미검증 범위는 [검증 기록](../payment/verification.md)에 구분한다.
