# 0019. P3 Case 조회 · 판정 HTTP 계약

- **상태:** 제안
- **날짜:** 2026-10-23 (2026-10-26 #160 개정: 판정 직원을 로그인 세션으로 · #161 개정: 완료 기반 인계와 다시 확인)
- **관련:** Issue #98 · #107 · #160 · #161, ADR-0020, ADR-0027, PR #103 · #113, ADR-0009, Notion FDS 정본 02 §2.5 · 03 §3.5, Notion "목업 데이터 · 연동 현황"
- **영향 받는 담당:** P3 수민 (소유), P1 주환 (frontend · identity), P4 현규 (보고서), P5 시은 (금융 조치 결과)

## 배경

보안 직원 Case 화면(#107)이 목업 대신 `/api/security-cases`를 쓰게 되면서, 화면과 백엔드 사이에 HTTP 계약이 생겼다.
이 API는 P5 결제 정보 · P2 FDS 평가 · P4 보고서를 합쳐 보여 주는데, 지금은 셋 다 P3 목업(`MockPaymentInfoStore` · `MockReportGateway`, 메모리)이다.
백엔드를 다시 띄우면 Case · 판정 · 인계는 DB에 남지만 목 결제 정보와 보고서는 사라진다. 이때 일부 값만으로 합계를 내거나, 결제 0건 보고서를 성공으로 내보내면 직원이 잘못된 근거로 판정할 수 있다(#113 리뷰).
금융 조치 인계는 #161부터 목업이 아니라 P5 `PaymentActionRequester` · `PaymentActionResult`(ADR-0027)를 쓴다. P4 보고서 요청 계약(ADR-0009)은 아직 없다. P1 로그인 사용자 조회 공개 경계는 ADR-0020(`identity.api.CurrentStaff`)으로 생겼고, 판정 직원 확인에 쓴다(#160).

## 결정

P3는 아래 계약을 `investigation` 모듈의 HTTP API로 둔다. 다른 모듈 데이터는 조회하지 못하면 추정하지 않고 `null`로 내보낸다.

| 메서드 · 경로 | 용도 |
| --- | --- |
| `GET /api/security-cases?merchantId=` | Case 목록 (`merchantId`는 표시 필터, 인가 아님) |
| `GET /api/security-cases/{caseId}` | Case 상세 — 결제 · 보고서 전 버전 · 판정 · 인계 · 이력 |
| `POST /api/security-cases/{caseId}/decisions` | 결제별 ACCEPT · REJECT 판정, 반영된 상세 반환 |
| `POST /api/security-cases/{caseId}/reports` | 현재 증거 버전 보고서가 없을 때만 다시 요청, 반영된 상세 반환 |
| `POST /api/security-cases/{caseId}/handoffs/{handoffId}/recheck` | 금융 처리 대기 중인 인계를 같은 멱등키로 다시 확인, 반영된 상세 반환 (#161) |
| `POST /api/demo/held-payments` | 시연 전용 50~79점 HELD 결제 (P5 결제 입력이 켜지면 #163에서 삭제) |

- 결제 정보 누락: 상세 결제의 `amount` · `paymentStatus` · `requestedAt` · `riskScore` · `fdsResult`는 `null`, `ruleHits`는 `[]`.
  목록의 `totalAmount` · `maxRiskScore`는 연결 결제 중 하나라도 값을 모르면 `null`이다. 일부만 더하거나 남은 결제 최댓값을 내보내지 않는다.
- 보고서 재요청: 최신 보고서의 `evidenceVersion`이 현재 Case보다 낮거나 보고서가 없을 때만 요청한다. 이미 있으면 새로 만들지 않고, 종료된 Case는 그대로 둔다. 요청은 외부 호출이라 트랜잭션 밖에서 한다.
- 목 보고서: 연결 결제 정보를 하나라도 찾지 못하면 `SUCCEEDED`가 아니라 `FAILED`(본문 `null`)로 남긴다. 직원은 정본 02 §2.5의 수동 판정(`reportId: null`)으로 간다.
- 판정 직원은 요청 본문으로 받지 않는다. 서버가 `CurrentStaff`로 로그인 사용자를 확인해 `staff_id`로 저장한다. 본문에 `staffId`가 있어도 무시한다(#160).
- 판정은 `SECURITY_STAFF`만 할 수 있다(정본 02 §2.8 · §2.12). 로그인하지 않았으면 `INV_010` 401, 다른 역할(OPERATOR · ADMIN)이면 `INV_009` 403이다. ADMIN 허용이 필요해지면 허용 역할 조건만 바꾼다.
- 인계 상태는 완료 기반이다(ADR-0027). `REQUESTED`는 금융 처리 대기 중, `ACCEPTED`는 실제 매입 · 취소 완료, `FAILED`는 확정 거절이다. 모든 인계가 `ACCEPTED`가 되어야 Case가 `CLOSED`가 된다.
  - P5 응답 `PENDING`은 정상 대기다. 인계 `REQUESTED` · Case `ACTION_PENDING`을 두고 예외 · 자동 재요청을 하지 않는다.
  - 접수 단계 거절 6개(`PAYMENT_NOT_FOUND` · `PAYMENT_NOT_HELD` · `PAYMENT_NOT_AUTHORIZED` · `IDEMPOTENCY_KEY_CONFLICT` · `PAYMENT_ACTION_CONFLICT` · `DECISION_ACTION_CONFLICT`)는 응답으로만 오므로 응답에서 반영한다. 충돌 3개는 P3 버그 신호라 오류 로그를 남긴다.
  - 그 밖의 완료 결과(매입 완료 · 취소 완료 · `VOID_DECLINED`)는 결과 이벤트로만 반영한다. 응답에는 `completedAt`이 없어서, 응답을 먼저 반영하면 실제 완료 시각이 이력에 남지 않는다.
  - 결과 이벤트는 멱등키로 인계를 찾고 `paymentId` · `decisionId` · `actionType`을 대조한다. 다르거나, 완료된 인계에 다른 `targetOperationId` · 거절이 오면 반영하지 않고 예외로 끝내 `EVENT_PUBLICATION`에 미완료로 남긴다(P5와 대조할 기록). 같은 결과가 다시 오면 무시한다.
  - `acceptedAt`은 P3 반영 시각이다. P5 `completedAt`은 `case_histories.event_data`에 남긴다.
  - 다시 확인이 이벤트보다 먼저 같은 결과를 반영하면 그 이력에는 `completedAt`이 없다. 뒤에 온 결과 이벤트는 상태를 바꾸지 않고, 이 인계 이력에 `completedAt`이 아직 없을 때만 `HANDOFF_COMPLETION_RECORDED`(handoffId · completedAt)를 한 번 남긴다(PR #194 리뷰).
- 다시 확인: `REQUESTED` 인계만 대상이다. 보안 직원만 부를 수 있고(판정과 같은 확인), 같은 멱등키로 P5에 저장된 결과를 받아 반영한다. P6 매입을 다시 실행하지 않고 새 키도 만들지 않는다. 계속 `PENDING`이면 상태는 그대로 두고 `last_attempted_at`과 `HANDOFF_RECHECKED` 이력(확인한 직원 · 결과)만 남긴다. 시간이 지났다고 `FAILED` · `CLOSED`로 바꾸지 않는다(#158).
- 시연 결제는 P5 `payments`에 없다. 시연 화면에서만 `investigation.mock.payment-action-enabled=true`로 목 금융 조치(바로 완료)를 켠다. 기본값은 실제 P5다.
- 오류는 `code`가 있는 problem 응답이다: `INV_001` · `INV_011` 404, `INV_002` · `INV_003` · `INV_004` · `INV_007` · `INV_012` 409, `INV_005` · `INV_006` · `INV_008` 400, `INV_009` 403, `INV_010` 401, `INV_013` 503. 화면은 `INV_003` · `INV_004`를 "다시 확인 후 판정"으로 처리한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 누락 금액을 0으로 더해 숫자 계약 유지 | 고액 Case가 0원으로 보여 우선순위를 잘못 정한다 |
| 숫자 합계 + `incomplete` 플래그 | 화면이 플래그를 놓치면 같은 오인이 생긴다. 모르는 값은 `null` 하나로 표현하는 편이 안전하다 |
| 상세 조회 때 목 보고서를 자동으로 다시 만들기 | 조회가 상태를 바꾸고, 낡은 버전 요청 건너뛰기(stale) 규칙과 충돌한다 |
| 결제 정보가 없어도 결제 0건 보고서를 `SUCCEEDED`로 생성 | 증거가 빠진 보고서가 판정 근거로 인정되어 금융 조치까지 인계된다 |
| `staffId`를 화면이 `/auth/me`로 매번 다시 조회 | `/auth/` 401은 세션 만료 처리로 전달되지 않아, 만료 뒤 판정이 계속 실패만 한다 |
| (#160) 화면이 보낸 `staffId`를 계속 믿기 | 다른 사람 ID나 비로그인 상태로 판정을 남길 수 있다. 판정 기록은 금융 조치로 이어지므로 서버가 확인해야 한다 |
| (#161) 응답과 결과 이벤트 중 먼저 온 쪽으로 반영 | 화면은 바로 바뀌지만, VOID는 거의 항상 응답이 먼저라 실제 완료 시각(`completedAt`)이 이력에 남지 않는다 |
| (#161) 오래 대기한 인계를 자동 `FAILED`로 바꾸거나 자동 재요청 | 늦게 매입이 끝나면 P3 기록과 실제 결제가 어긋난다. P3에는 P5 · P6 작업을 멈출 권한이 없다(정본 §2.9, #158) |
| (#161) 목 금융 조치를 바로 지우기 | 시연 결제가 모두 `PAYMENT_NOT_FOUND`로 실패한다. P5 결제 입력이 켜질 때까지(#163) 속성으로만 켠다 |
| (#160) ADMIN도 판정 허용 | 정본의 판정 주체는 보안 직원이고 화면에도 ADMIN 진입 메뉴가 없다. 열어 두었다가 나중에 막으면 그 사이 판정을 다시 검토해야 하므로, 막아 두고 필요할 때 연다 |

## 영향

- API: 이 문서의 표. 소비자는 이 저장소 frontend뿐이다. `totalAmount`는 #103의 `long`에서 nullable로 바뀌었다(병합 전 #113에서 변경, 다른 소비자 없음).
- DB: 없음. 목업 데이터는 메모리이고 `ai_reports`(P4 소유)는 쓰지 않는다. 이력 종류 `HANDOFF_RECHECKED` · `HANDOFF_COMPLETION_RECORDED`는 `event_type` VARCHAR 값만 추가한다.
- 화면: `src/api/cases.ts`가 숫자 ID를 문자열로 바꾸고, `null`은 `-`로 표시한다. 현재 버전 보고서가 없으면 "AI 보고서 다시 요청" 버튼을 보인다. 인계가 `REQUESTED`면 "금융 처리 대기 중"과 "다시 확인" 버튼, `FAILED`면 거절 코드 문구를 보이고, 이력에 실제 완료 시각을 표시한다.
- 테스트: `CaseFlowTests`(목록 `null` 합계 · 최고 점수, 재요청 1회 · FAILED, 시연 설정의 목 금융 조치), `HandoffReceiptTests`(결과 이벤트 · 다시 확인), `PaymentActionFlowTests`(실제 P5 · P6 연결).
- 문서: P4 보고서 계약이 정해지면 보고서 재요청과 FAILED 의미를 그 계약에 맞춰 이 ADR을 개정한다. `staffId` 요청값은 #160에서 없앴다. 시연 `merchantId: 1` 가정은 #163에서 없앤다.

## 검증

- `./gradlew spotlessApply check mariaDbTest` — PR #113 커밋 기준 결과는 PR 본문 "테스트 결과"에 적는다
- `npm run lint && npm run format:check && npm run build` — 같음
- 미검증: 조회 API의 인가(역할 · 가맹점) 없음 — 판정 · 다시 확인 API만 역할을 확인한다(#160 · #161). P4 실제 연동 전이라 보고서는 목업 기준으로만 확인. P5 금융 조치는 #161에서 실제 구현으로 확인했지만, 결제 정보 조회는 아직 목업이다(#163)
- #160: 판정 권한 테스트는 `CurrentStaff`를 대역으로 바꿔 확인했다. 실제 로그인 세션 → `CurrentStaff` 연결은 P1 테스트(ADR-0020)를 따른다
