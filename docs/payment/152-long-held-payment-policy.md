# #152 장기 보류 결제 종료·취소 정책 초안

- **상태: 전체 정책은 제안 — P5 D1~D6 및 내부 조회의 P1·P2·P3·P6 확인 완료. 시간·운영 담당·CakeShop 합의·종료 계약·구현은 미완료.**
- 작성: 2026-10-26. 코드 대조 기준: `dev 35585fea112fac2853c205e29f2a4a5adf391cb0`.
- 관련: [Issue #152](https://github.com/team-sweethan/DonWorry/issues/152), [ADR-0035](../decisions/0035-long-held-payment-policy.md), [담당자 확인 질문](152-policy-confirmation.md).
- 내부 조회의 담당 확인·정보·권한·후속 이슈는 [조회 설계](152-payment-operations-design.md)와 [회신 기록](152-policy-confirmation.md)을 따른다. 운영 원칙은 [내부 대응 기준](152-internal-response.md)에 기록한다.
- 선행 맥락: [#149 요청·조회](149-cakeshop-payment-api.md), [직원 조치](141-payment-actions.md), [ADR-0027](../decisions/0027-payment-actions.md).

## 1. 목적과 확정 수준

결과 미확인·PENDING·HELD가 오래 지속될 때 확인 담당, 종료 조건, CakeShop 예약 처리와 경합 대응을 합의한다. **확인 기한을 넘으면 담당자가 개입하되 시간 초과만으로 금융 실패·취소나 예약 해제를 확정하지 않는 방향**을 제안한다.

전체 정책은 제안 상태다. 2026-10-26 사용자가 내부 D1~D6의 시간·담당 외 추천 방향을 확정했다. 확정 범위와 미정 값은 [내부 대응 기준 §2](152-internal-response.md#2-p5-d1d6-확정-범위)에 기록한다. 이후 내부 조회 계약은 담당자가 확인했다고 사용자가 회신했다. 확인 범위는 조회 설계에 한정하며 구현·운영 활성화·CakeShop 예약 합의로 확대하지 않는다. 최대 보류 기간, 조회·재시도 주기, 재고·쿠폰 유지 한도는 미정이다. 기존 10분 접수 기한과 P3의 24시간 CAPTURE 경고 방침을 새 종료 기한으로 확대하지 않는다.

이 단계에서 API·스케줄러·상태값·테이블을 구현하지 않는다. 고객 취소·환불 계약은 [#153](https://github.com/team-sweethan/DonWorry/issues/153), 별도 결과 통지 도입 판단은 [#154](https://github.com/team-sweethan/DonWorry/issues/154)에서 다룬다. #149의 실제 인증·요청·조회 검증은 해당 이슈에 유지한다.

## 2. 현재 구현 근거

이 절은 아래 로컬 기준의 기록이다. 2026-10-26 최신 `dev 2ba8a4f`에는 P3 HELD 수신·Case 연결이 #170으로 병합됐다. 직원 금융 Gateway·결과 수신은 #161 후속이다. 이번에 범위를 좁혀 정리한 [돈워리 내부 대응 기준](152-internal-response.md)에서 최신 진행과 P5 확정 범위·P3/P6 합의 항목을 확인한다. CakeShop 관련 제안은 이 문서에 보존한다.

아래는 코드 확인 사실이며 이번 작업에서 실행 검증한 결과는 아니다.

| 구현 사실 | 근거 | 정책상 제약 |
| --- | --- | --- |
| 최초 주문 시각 +10분 이전에만 새 시도 접수. 같은 키 재사용은 기한 검사보다 먼저 처리 | [PaymentOrder](../../backend/src/main/java/com/donworry/payment/entity/PaymentOrder.java), [PaymentRequestTransactionService](../../backend/src/main/java/com/donworry/payment/service/PaymentRequestTransactionService.java) | 기존 시도 자동 취소 기한이 아니다. 새 시도는 이전 DENIED/DECLINED일 때만 허용하며 VOIDED/EXPIRED도 차단한다 |
| PENDING에는 미평가·평가 오류·승인 후 매입 대기가 포함됨 | [PaymentRequestProcessingService](../../backend/src/main/java/com/donworry/payment/service/PaymentRequestProcessingService.java), [Payment](../../backend/src/main/java/com/donworry/payment/entity/Payment.java) | 결제 상태만으로 승인 여부·종료 가능 여부를 판단할 수 없다 |
| 같은 키 POST는 중단 단계 재개를 시도. NEEDS_REVIEW는 자동 재평가하지 않음 | [PaymentRequestFlowService](../../backend/src/main/java/com/donworry/payment/service/PaymentRequestFlowService.java), [PaymentRequest](../../backend/src/main/java/com/donworry/payment/entity/PaymentRequest.java) | GET만으로 처리가 재개되지 않으며 같은 키 반복이 FDS ERROR를 해결하지 않는다 |
| 직원 CAPTURE 접수 후에도 완료까지 HELD 유지. 결제당 한 조치이며 다른 조치 차단 | [PaymentActionAdmissionService](../../backend/src/main/java/com/donworry/payment/service/PaymentActionAdmissionService.java), [PaymentActionTransactionService](../../backend/src/main/java/com/donworry/payment/service/PaymentActionTransactionService.java) | HELD만 보고 VOID를 접수할 수 없다. FAILED 조치도 반대 조치로 바꾸는 기능은 없다 |
| P6 매입 완료·정산 예정금·완료 발행은 같은 트랜잭션. P5 완료 반영은 별도 | [CaptureTransactionService](../../backend/src/main/java/com/donworry/settlement/service/CaptureTransactionService.java), [PaymentCaptureService](../../backend/src/main/java/com/donworry/payment/service/PaymentCaptureService.java) | P5 대기 중에도 P6 매입은 끝났을 수 있다 |
| VOID는 승인된 HELD의 직원 모의 취소. 성공 뒤 VOIDED. EXPIRED 전이 없음 | [Payment](../../backend/src/main/java/com/donworry/payment/entity/Payment.java), [PaymentActionTransactionService](../../backend/src/main/java/com/donworry/payment/service/PaymentActionTransactionService.java) | PENDING 종료·고객 취소·주문 만료에 재사용할 수 없다. EXPIRED는 문서상 승인 만료 의미 유지 |
| P3 금융 Gateway는 즉시 ACCEPTED를 반환하는 목업 | [MockFinancialActionGateway](../../backend/src/main/java/com/donworry/investigation/service/mock/MockFinancialActionGateway.java), [HandoffService](../../backend/src/main/java/com/donworry/investigation/service/HandoffService.java) | 현재 Case 종료를 실제 금융 성공 증거로 사용할 수 없다 |

[ADR-0027](../decisions/0027-payment-actions.md)의 #158 회신은 완료 기반 인계 방침의 근거다. 위 표는 `35585fe` 기준이다. 이후 HELD 수신·Case 생성은 [PR #170](https://github.com/team-sweethan/DonWorry/pull/170)으로 dev에 병합됐다. 직원 금융 Gateway·결과 수신·인계 재확인은 [#161](https://github.com/team-sweethan/DonWorry/issues/161)의 후속 범위다. 상세 구분은 [내부 대응 기준](152-internal-response.md)을 따른다. 목업 ACCEPTED를 실제 금융 성공으로 보지 않는다. CakeShop 코드·예약 동작·Notion 최신 내용은 이번 작업에서 확인하지 않았다.

## 3. 기본 정책 제안

1. 주문 접수 기한, 경고·대응 기한, 실제 금융 종료를 구분한다. 시간 경과는 확인·인계 사유다.
2. 결과 미확인은 관측 상황으로 취급한다. HTTP 타임아웃·500/503·이벤트 지연을 최종 금융 실패로 바꾸지 않는다.
3. 종료 전에 결제 상태, 요청 처리 단계, 승인 참조, 직원 조치, P6 매입 결과와 원래 전달 기록을 대조한다. 모듈 간 확인은 담당자의 공개 경계로 제공하며 타 모듈 Repository를 참조하지 않는다.
4. 실제 매입 완료가 확인되면 그 사실을 반영한다. 주문 이행이 불가능하면 #153의 환불 판단 대상으로 연결하며 환불 성공을 미리 가정하지 않는다.
5. 고객·주문 취소를 부정거래 REJECT로 기록하지 않는다. 미승인 PENDING 종료, 고객·자동 취소와 직원 조치 변경은 별도 계약·구현이 필요하다.
6. 금융·조사 기록은 보존한다. 수동 대응에서도 DB 상태 직접 변경이나 미완료 이벤트 기록 삭제로 완료를 만들지 않는다.

## 4. 원인별 대응과 담당 제안

| 상황 | 확인 담당 | 대응·종료 조건 |
| --- | --- | --- |
| CakeShop 응답 유실·접수 여부 미확인 | CakeShop → P5 | 원래 주문·키·본문 보존. 식별자를 확보했으면 조회하고, 필요하면 같은 키 POST로 확인·재개. 새 키 생성·예약 해제 보류 |
| PENDING + RECEIVED/EVALUATED | P5, 평가 문제는 P2 | 같은 키로 복원 시도. 재시도 상한 후 담당자 확인. 미승인 종료 기능이 없으므로 임의 DENIED/EXPIRED 금지 |
| PENDING + NEEDS_REVIEW | P5·P2 | 저장 ERROR 확인. 자동 ALLOW·새 UUID 재평가·강제 거절 금지. 재평가/종료는 별도 합의 |
| 승인된 PENDING, 자동 매입 대기 | P5·P6 | P6 결과·요청/완료 전달 기록 확인. 원래 전달 복구. 같은 POST로 새 매입 이벤트를 만들지 않음 |
| HELD, 직원 조치 없음 | P3, 금융 실행 P5 | 직원 판정 진행. REJECT 후 실제 VOID 성공 확인 시 취소 완료. 장기 보류만으로 자동 REJECT하지 않음 |
| HELD, 직원 CAPTURE PENDING | P3·P5·P6 | 금융 대기 유지. 같은 조치 키는 저장 결과 확인. 매입 재발행·시간 초과 VOID 전환 금지 |
| VOID 확정 거절 | P5·P3 | 결제 HELD·조치 FAILED 보존. 같은 키는 저장 실패 반환. 취소 완료 안내·예약 해제 금지. 후속 해결 경로 미정 |
| CAPTURED 또는 P6 실제 완료 확인 | P5·P6, 주문 반영 CakeShop | 완료 전달·상태 반영. 이행 불가 시 환불 협의. 완료 사실을 시간 초과로 되돌리지 않음 |
| DENIED/DECLINED/VOIDED | P5 결과 확인, CakeShop 반영 | 주문·현재 시도 대조 후 예약 해제. 재결제는 기존 #149 조건과 재확보 확인 필요 |

금융 결과 미확정 상태를 조회 한도 도달만으로 조회 대상에서 제거하지 않는다. 반복 조회에서 담당자 추적으로 넘기는 방법과 최종 해소 확인 담당을 함께 정한다. 운영용 조회·재전달 수단은 아직 제공됐다고 가정하지 않는다.

## 5. 시간 기준 제안과 미정 수치

| 측정 대상 | 시작 기준 제안 | 제한 |
| --- | --- | --- |
| 전체 미처리 | 최초 결제 requestedAt | 같은 키 재전송으로 초기화하지 않음 |
| HELD 판정 대기 | HELD 전이 statusChangedAt | 직원 CAPTURE 후에도 같은 시각이므로 조치 대기와 구분. 과거 null 추정 금지 |
| 직원 금융 조치 대기 | payment_actions.requestedAt | 결제 접수 시각과 구분 |
| 자동 매입 대기 | 원래 매입 요청 발행 시점 | 승인 후 PENDING 유지 시 statusChangedAt은 갱신되지 않음. 운영 추적·보존 방법 별도 설계 |
| 재고·쿠폰 예약 | CakeShop 실제 예약 시작 시각 | orderCreatedAt과 같다고 가정하지 않음 |

담당자별로 최초 경고 시점, 대응 기한, 서버 GET 간격, 같은 키 POST 재시도 간격·상한, 예약 유지 한도를 회신받는다. 현재 10분은 새 시도 접수 기한이다. 24시간은 ADR-0027의 P3 CAPTURE 대기 경고 방침이며 전체 최대 보류·자동 취소 기한이 아니다. 화면 5초 조회 역시 제안이다.

예약 한도에 도달해도 금융 결과 미확정이면 자동 해제하지 않고 예외 대응으로 인계하는 안이다. 실제로 예약을 유지할 수 없는 업무가 있으면 활성화 전에 해당 주문의 접수 제한·이행 불가 대응을 공동 결정한다. 예약을 무기한 유지할 수 있다고 가정하지 않는다.

## 6. CakeShop 예약과 경합 처리

예약·해제·재확보·주문 이행은 CakeShop, 금융 결과 제공은 P5, 매입·정산 사실은 P6, 판정·Case는 P3 소유다. 예약 유지 방식은 CakeShop 추가 수락 전이다.

| 조건 | 제안 처리 |
| --- | --- |
| 결과 미확인/PENDING/HELD | 예약 유지 및 담당 확인. 고객 화면 종료·통신 타임아웃만으로 해제하지 않음 |
| 최종 거절·취소 | 가맹점·주문·paymentId·attemptNo와 현재 시도를 대조하고 한 번만 해제. 재결제 전 재확보 |
| CAPTURED | 예약을 주문 이행으로 확정. 이미 예약이 풀렸다면 재확보 가능 여부를 확인하고 불가 시 #153 예외로 연결 |
| 직원 ACCEPT/REJECT 동시 요청 | 현재 결제 잠금·결제당 한 조치 근거로 접수된 조치 유지. 반대 조치 변경 미지원 |
| 미래 고객/자동 취소와 매입 경합 | 매입 발행과 취소 접수를 함께 통제하는 영속 근거·원자성 필요. P6 접수/완료와 교차 경합은 별도 합의. 화면 요청 순서·조회 후 판단만으로 해결하지 않음 |
| P6 완료 후 P5 반영 지연 | 원래 완료 전달 복구. P5 대기 상태만 보고 VOID하지 않음 |
| 중복·역순·이전 시도 결과 | 같은 결과 한 번만 반영. 완료를 늦은 대기 응답으로 되돌리지 않음. 이전 시도의 거절로 현재 시도 예약 해제 금지 |
| 상충하는 성공 결과·식별자 불일치 | 자동 덮어쓰기 중단, 기록 보존 후 P5·P6 및 영향 담당 확인 |

## 7. 활성화 전 조건과 후속 작업 제안

현재 수동 대응도 기존 결과 조회·검증된 원래 전달 복구·허용된 직원 조치 범위로 제한한다. 미승인 PENDING 종료, VOID 실패 해소, 매입 후 환불은 아직 가능한 운영 기능으로 안내할 수 없다.

| 후속 범위 | 담당 제안 | 선행 관계·이슈 상태 |
| --- | --- | --- |
| 실제 인증·요청·조회·CakeShop E2E | P1/CakeShop·P5 | 기존 [#149](https://github.com/team-sweethan/DonWorry/issues/149) 유지. 이 정책의 활성화 조건과 함께 확인 |
| HELD·결과 운영 수신, 완료 기반 Case 처리·경고 | P3·P5 | HELD 수신은 PR #170 병합. 직원 Gateway·결과 연결은 #161 후속. 기존 #158 합의와 실제 검증 구분 |
| 미처리 조회·오류 조사·복구 | P5, P2·P6 | P5 조회 #188, P2/P5 결과 복원·재평가 설계 #190, 운영 복구 설계 #191. PENDING 종료는 별도 합의 |
| 매입 결과 확인·재전달 운영·확정 실패 계약 | P6·P5 | 공개 조회 #189, 재전달 운영 #191. CAPTURE 확정 실패는 ADR-0027의 후속 협의 상태 유지 |
| 예약 한도·서버 확인·예외 주문 대응 | P1/CakeShop | 유지 가능 범위 및 접수 제한 합의 후 CakeShop 저장소 작업 연결 |
| 고객 취소·환불 계약과 경합 | P1/CakeShop·P5·P6, 영향 시 P3 | 기존 [#153](https://github.com/team-sweethan/DonWorry/issues/153). 이 문서가 계약을 확정하지 않음 |
| 별도 통지 필요성 | P1/CakeShop·P5 | 기존 [#154](https://github.com/team-sweethan/DonWorry/issues/154). 통지 도입 미확정 |

실제 활성화 전에 발견 방법·대응 담당·인계·복구 절차와 예약 유지 불가/VOID 실패 처리 기준을 확보하는 안이다. 최종 범위·운영 제한의 수락 주체와 회신 근거를 남긴다. 후속 이슈가 없거나 담당 동의가 없으면 #152 완료로 표시하지 않는다.

## 8. 합의 후 검증 계획

| 시나리오 | 확인할 결과 |
| --- | --- |
| POST 응답 유실 후 기한 경과·같은 키 복원 | 시도/승인 중복 없음. 기존 결과 확인. 예약 해제 없음 |
| RECEIVED/EVALUATED 중단 및 NEEDS_REVIEW 장기 유지 | 단계별 복원과 수동 확인 구분. 새 UUID·자동 승인/거절 없음 |
| 경고·대응·예약 한도 직전/정각/직후 | 시간 기준·같은 키 불변·담당 인계 확인. 금융 결과를 시간만으로 변경하지 않음 |
| HELD 직원 판정 지연, CAPTURE/VOID 동시 접수 | 판정 대기와 금융 대기 구분. 반대 조치 이중 성공 없음 |
| P6 롤백·확정 실패·완료 후 P5 반영 실패 | 기술 실패와 확정 실패 구분. 원래 전달 복구. 실패 계약은 합의 후 검증 |
| 취소 요구 뒤 늦은 CAPTURED, 예약 해제 후 완료 | 실제 결과 보존·주문 예외 추적. 환불 완료는 별도 확인 |
| VOID 거절·기술 예외·커밋 실패 | HELD/조치 결과 보존 또는 원자 롤백. 가짜 취소 완료 없음 |
| 결과 재전달·순서 역전·과거 시도 응답 | 단일 반영·완료 보존·현재 시도 예약 보호 |
| P3 실제 수신과 CakeShop 반영 부분 실패 | Case/주문 반영 복구. 전달 실패를 금융 실패로 바꾸지 않음 |

## 9. 이번 작업의 검증 범위

문서만 변경했다. 코드 대조는 §2의 기준 커밋이며 자동 종료·새 계약·운영 복구 구현을 검증한 것이 아니다. 문서 검사 명령·결과는 변경 보고에 기록한다. backend·MariaDB 실행 검사는 코드 변경 없는 이번 단계에서는 해당 없음이다. CakeShop 실제 동작, P3 운영 연결, 장애·경합 E2E와 Notion 동기화는 미검증이다.
