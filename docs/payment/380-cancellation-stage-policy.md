# #380 결제 진행 단계별 취소 정책

상위 #153, 공통 기반 #381, HELD #382, CAPTURED #383. 정책 근거는 [ADR-0054](../decisions/0054-full-cancellation-policy.md)와 [ADR-0055](../decisions/0055-inflight-cancellation-policy.md)다.

2026-10-31 사용자가 **진행 중에는 잠시 거절하고 조회 후 재요청**하는 방식을 선택했다. 이번 작업은 정책 단계이며 API·DB·금융 처리 구현은 하지 않는다. 아래 코드 사실은 `dev 8be3df5` 기준 읽기 결과이고 실행 검증이 아니다.

후속 구현: #381의 [접수·조회 Service](381-cancellation-storage.md#11-접수조회-service-구현)에서 일시 거절 기록·키 경합·기존 결과 조회와 직원 매입 선행 잠금을 연결했다. [HTTP/HMAC 연결](381-cancellation-storage.md#12-httphmac-연결)에서 같은 키 재요청·조회와 정지 가맹점 허용을 검증한다. 아래 미구현 표시는 정책 작성 시점 기준이며, 실제 고객 VOID/REFUND 실행과 반대 방향 경합은 여전히 후속이다.

## 1. 현재 코드에서 확인한 사실

- [Payment](../../backend/src/main/java/com/donworry/payment/entity/Payment.java)의 `recordAuthorizationSuccess()`는 자동 매입이면 승인 참조를 저장하면서 PENDING을 유지하고, 직원 판정 대상이면 HELD로 바꾼다.
- [PaymentAuthorizationService](../../backend/src/main/java/com/donworry/payment/service/PaymentAuthorizationService.java)는 결제 쓰기 잠금 안에서 자동 매입 승인 저장과 CaptureRequested 발행을 같은 트랜잭션으로 묶는다.
- [PaymentActionAdmissionService](../../backend/src/main/java/com/donworry/payment/service/PaymentActionAdmissionService.java)와 [PaymentActionTransactionService](../../backend/src/main/java/com/donworry/payment/service/PaymentActionTransactionService.java)는 결제 잠금을 유지하며 직원 CAPTURE 조치와 요청 이벤트를 저장한다. 결제는 완료 통지 전까지 HELD다.
- [PaymentCaptureService](../../backend/src/main/java/com/donworry/payment/service/PaymentCaptureService.java)는 완료 이벤트를 받아 결제를 잠근 후 CAPTURED를 반영한다. P6가 먼저 완료되어도 P5 반영이 늦을 수 있다.
- [운영 조회 분류](../../backend/src/main/java/com/donworry/payment/repository/PaymentOperationsRepository.java)는 승인 참조·요청 단계·직원 조치를 조합하여 EVALUATION_PENDING, EVALUATION_ERROR, AUTO_CAPTURE_PENDING, DECISION_PENDING, STAFF_CAPTURE_PENDING 등을 구분한다. 이 조회 분류 자체가 취소 실행 인가나 잠금은 아니다.
- [#189 조회](../settlement/189-capture-read-api.md)는 읽기 전용 스냅샷이다. 빈 결과는 매입 요청 없음의 증거가 아니며 DB 예외는 빈 결과와 다르다.

## 2. 단계별 처리 기준

아래 설명은 업무 의미이며 새 HTTP 코드·응답 enum·DB 상태값을 확정하는 표가 아니다. 요청 권한·가맹점 확인을 먼저 수행하고 실제 판단은 잠금 아래 최신 저장 기록을 확인한다.

| 관측 단계 | 취소 요청 처리 | 이후 행동 |
| --- | --- | --- |
| PENDING, 승인 참조 없음, RECEIVED/EVALUATED | 진행 중으로 일시 거절. 취소 예약·성공 처리 없음 | 정상 처리 결과를 조회한 뒤 재요청 |
| PENDING, 승인 참조 없음, NEEDS_REVIEW + ERROR | 오류 복구 필요로 거절. 정상 처리 중과 구분 | 기존 FDS 복구 절차로 결과 확인 후 재요청. 자동 종료·복구 기한은 약속하지 않음 |
| PENDING, 승인 참조 있음, 자동 매입 경로 | 매입 결과 대기이므로 일시 거절 | P5에 매입 완료가 반영된 뒤 전액 환불 재요청 |
| HELD, 승인 참조 있음, 직원 조치 없음, 기록 모순 없음 | 승인 취소 후보로 #382에 연결 | 잠금 아래 매입 요청이 없는지 재확인 후 실행 |
| HELD, 직원 CAPTURE 조치 PENDING | 매입 진행 중이므로 일시 거절 | 완료 결과 확인 후 #383 전액 환불 재요청 |
| P5는 PENDING/HELD인데 P6 조회는 COMPLETED | 승인 취소하지 않고 결과 반영 확인 필요로 거절 | 기존 완료 전달·복구로 P5 기록을 일치시킨 뒤 재요청. 조회만으로 P5 상태를 수정하지 않음 |
| CAPTURED이며 기록이 일치 | #383 전액 환불 대상으로 연결 | 정산 완료 여부로 차단하지 않음 |
| VOIDED 또는 향후 환불 완료 기록 | 새 금융 실행 없이 기존 확정 결과 확인·반환 | #381에서 기존 직원 VOID와 고객 취소 기록 차이를 처리 |
| DENIED/DECLINED | 취소할 승인·매입이 없는 종료 건으로 안내 | 취소 성공으로 꾸미지 않음 |
| EXPIRED, 실패한 직원 조치, 누락·모순·알 수 없는 조합 | 확인 필요로 거절 | 저장 사실과 해당 실패 계약 확인 후 처리. 재시도 가능한 것으로 임의 분류하지 않음 |
| P6 조회 오류 또는 잠금 실패 | 기술 오류로 처리, 금융 실행 없음 | 재조회·복구. 실패를 매입 전으로 바꾸지 않음 |

PENDING의 승인 참조 유무만으로 모든 기록을 판정하지 않는다. 요청 단계와 직원 조치 등이 모순되면 확인 필요 분기로 보낸다. 직원 VOID 실패의 고객 재취소 허용 여부는 #382에서 P3 계약과 함께 정한다.

## 3. 일시 거절의 의미와 재요청

- 요청을 장시간 열어 두거나 예약해 두지 않는다. 거절된 요청이 나중에 자동으로 승인 취소·환불을 실행하지 않는다.
- 거절 응답은 결제가 실패했거나 취소됐다는 의미가 아니다. CakeShop은 이를 근거로 주문 취소 완료·재고/쿠폰 반환을 확정하지 않는다. 화면 반영 계약은 P1/CakeShop 확인 대상이다.
- 이후 HELD로 확정되면 승인 취소, CAPTURED로 확정되면 전액 환불 요청을 새로 판단한다. 무제한 자동 재요청이나 임의의 조회 간격을 도입하지 않는다.
- #381은 일시 거절 후 재요청의 요청 키 정책을 명시해야 한다. 기존 거절 결과를 영구 재사용하여 정상 재시도를 막거나, 같은 요청을 중복 실행하지 않도록 설계한다. 같은 키 재사용 여부·HTTP 상태·오류 코드·기록 형태는 이번 단계에서 확정하지 않는다.

## 4. 동시 실행 구현 조건

1. 고객 승인 취소·직원 조치·매입 완료 반영은 동일 결제 기준으로 직렬화하고 잠금 획득 후 상태와 조치를 다시 읽는다. 조회 화면에서 본 과거 상태로 금융 실행하지 않는다.
2. 직원 CAPTURE 요청 저장이 먼저 커밋되면 고객 승인 취소는 일시 거절한다. 고객 승인 취소가 먼저 성공 커밋되면 이후 직원 매입 요청은 취소된 결제에 실행되지 않아야 한다.
3. 매입 완료가 먼저 반영되면 최신 CAPTURED 흐름으로 판단한다. 고객 승인 취소를 진행 중 매입의 중단 수단으로 사용하지 않는다.
4. P5 결제 잠금이 P6 매입 실행까지 막는다고 가정하지 않는다. 이미 발행된 매입 요청은 철회하지 않으며 빈 P6 조회 결과도 철회 근거가 아니다.
5. 오류 복구·늦은 완료·결과 중복 수신 후에도 동일 금융 작업을 재실행하지 않는다. P6 정산 배치와 환불 성공 반영은 #233의 지급 제외/차감 단일 반영 계약을 따른다.

이는 후속 구현 요구사항이다. 현재 고객 취소의 잠금·기록·동시성 보장이 구현됐다는 뜻이 아니다.

## 5. 인수인계와 검증 계획

- #381: 공통 API·취소 기록·조회·키 충돌·재요청 계약. 이 문서의 업무 응답 구분을 구체화한다.
- #382: HELD 승인 취소와 직원 ACCEPT/REJECT 경합, P3 영향 확인.
- #383: CAPTURED 환불과 P6 성공 결과 전달. P5/P6 사실 불일치 시 복구 경계를 유지한다.
- #380 구현 단계: 평가/승인 중 요청, 자동 매입 대기, 직원 매입 대기, 늦은 완료, 잠금 선후, 데이터 모순·DB 오류를 단위·MariaDB 동시성 검사로 확인한다.
- P1/CakeShop: 일시 거절·오류 복구 필요의 안내와 조회 후 재요청 UX. P3: 고객 취소와 직원 판정 경합의 응답·Case 반영. P6: 결과 전달·복구 및 #233 계약. 담당 확인은 아직 완료되지 않았다.

문서 검사는 `python3 .github/scripts/check_md_links.py`, `git diff --check`로 수행한다. backend·MariaDB 검사는 정책 단계에서 미실시하며 #380 전체를 완료 처리하지 않는다.
