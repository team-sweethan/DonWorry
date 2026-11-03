# 0023. P5 HELD 이벤트 계약

- **상태:** 채택 — 2026-10-25 사용자가 전달한 P3의 필드·필수 조건 및 시각 기준 동의에 근거
- **날짜:** 2026-10-25
- **관련:** Issue #136, [ADR-0012](0012-payment-settlement-dependency.md), [P5 문서](../payment/README.md)
- **영향 받는 담당:** P5 시은 · P3 수민

## 배경

P5는 REVIEW 평가 후 모의 승인에 성공하면 HELD와 승인 참조를 저장하지만 P3에 통지하지 않는다. P3는 내부 HeldPayment와 CaseIntakeService로 Case 생성·병합·중복 연결 방지를 구현했으며, 현재 데모 진입점으로 사용한다. 직원 ACCEPT/REJECT는 이 통지로 Case가 생성된 뒤의 별도 단계다.

P3는 기존 HeldPayment의 7개 필드와 모두 필수라는 조건이 충분하다고 확인했다. Case 생성·30분 병합 판단은 기존 수신 처리 시각 기준을 유지하기로 했다.

## 결정

`payment.api.PaymentHeld`를 P5 → P3 사실 통지로 사용한다. `investigation → payment` 의존 방향은 유지한다.

| 필드 | 타입 | 의미·출처 |
| --- | --- | --- |
| paymentId | Long | 저장된 결제 ID |
| merchantId | Long | 저장된 결제의 가맹점 ID |
| customerRef | String | 저장된 결제의 고객 참조 |
| amount | Long | 저장된 결제 금액, 원 단위 |
| authorizationRef | String | 성공한 모의 승인 참조 |
| evaluationId | Long | 해당 REVIEW 평가의 ID |
| heldAt | Instant | P5가 HELD로 전환하는 시각, UTC |

모든 필드는 필수다. record 생성자에서 null을 거절하여 builder와 역직렬화에도 같은 필수 조건을 적용한다. 업무 값의 유효성·평가와 결제의 일치·승인 성공 여부는 P5 발행 서비스의 책임이다. DTO 생성만으로 실제 승인·저장이 입증되는 것은 아니다.

### 발행과 전달

- REVIEW 판정 후 모의 승인 성공으로 NOTIFY_HELD가 결정된 경우에만 발행한다. HELD·승인 참조 저장과 같은 업무 트랜잭션 안에서 발행한다.
- ALLOW, DENY, ERROR, 승인 거절·기술 실패에서는 발행하지 않는다. 이미 처리한 결제의 재승인 차단을 유지한다.
- heldAt은 최초 결제 생성 시각이나 P3 수신 시각이 아니다. 재전달 시 원래 이벤트 값을 유지한다. 별도 업무 컬럼을 추가하지 않는다.
- P3는 커밋 후 수신하여 내부 HeldPayment로 변환하고 기존 CaseIntakeService에 연결한다. 수신 리스너 구현은 P3 담당의 후속 작업이다.
- P3의 Case 생성·30분 병합 판단은 수신 처리 시각을 사용한다. 지연·재전달 시에도 heldAt으로 과거 시점의 병합을 재구성하지 않는다.
- 이미 연결된 paymentId는 기존 Case 연결을 유지한다. 새 이벤트 ID·시도 번호를 추가하지 않는다.
- 실패는 미완료 발행 기록으로 남기고 원래 기록을 재전달하는 방식으로 검증한다. 자동 복구 스케줄러는 이번 범위가 아니다.

### 수신자 등록 선행 조건

Modulith의 발행 기록은 등록된 트랜잭션 수신자를 대상으로 생성된다. P3 운영 리스너가 없는 상태에서 발행한 이벤트를 나중에 리스너를 추가하면 자동으로 받을 수 있다고 가정하지 않는다. 실제 REVIEW 흐름을 운영 연결하기 전에 P3 리스너 등록과 전달 검증을 함께 완료해야 한다. 테스트 수신자 검증은 P3 실제 Case 연결 검증과 구분한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P2 REVIEW 직후 통지 | 모의 승인 거절 건까지 Case 대상으로 만들 수 있다 |
| 직원 ACCEPT 후 HELD 통지 | 직원 검토 대상 Case를 만들기 위한 통지보다 판정이 앞서게 된다 |
| investigation.api에 이벤트 정의 | P5의 역방향 의존이 생기므로 ADR-0012에 어긋난다 |
| heldAt 기준으로 Case 병합 | P3가 기존 수신 처리 시각 기준 유지를 확인했다 |
| paymentId만 보내고 P3에서 나머지 조회 | 현재 공개 조회 계약이 준비되지 않았고 P3가 기존 7개 필드로 충분함을 확인했다 |

## 영향

- API: PaymentHeld record 추가. 기존 매입 이벤트와 HTTP 계약 변경 없음.
- DB: 업무 테이블·컬럼 변경 없음. 수신자 등록 시 기존 Modulith 발행 기록 사용.
- 화면: 이번 작업으로 목업 결제·평가 조회를 실제 조회로 교체하지 않는다.
- 테스트: 필수 필드 검증, 후속 발행 조건·필드 매핑·커밋·롤백·재전달 검사. P3 실제 연동은 별도 확인.
- 문서: P5 README와 ADR 목록에 계약과 구현 단계 구분.

## 검증

3번 단계에서 이벤트 타입과 필수 필드 검증 테스트를 추가했다. 실행 결과는 아래에 기록한다. 발행 서비스 연결·트랜잭션 전달·P3 실제 수신은 아직 미구현·미검증이며 4·5번 단계 및 P3 연동 작업에서 다룬다.

2026-10-25, `c19f94f` + #136 3번 단계 작업본에서 `cd backend && ./gradlew spotlessApply check` → BUILD SUCCESSFUL. PaymentHeldTests 7건 통과, 모듈 경계·계층 및 기존 일반 검사 통과. `python3 .github/scripts/check_md_links.py`와 `git diff --check` 통과. 이번 단계는 DTO·문서 변경으로 MariaDB 검사는 실행하지 않았으며 이벤트 전달·역직렬화 통합 검증은 후속 단계다.
