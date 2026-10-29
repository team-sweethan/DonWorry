# 0019. P3 Case 조회 · 판정 HTTP 계약

- **상태:** 제안
- **날짜:** 2026-10-23 (2026-10-26 #160 개정: 판정 직원을 로그인 세션으로 · #161 개정: 완료 기반 인계와 다시 확인 · #163 개정: 결제 · FDS 실제 조회 · #205 개정: 고객사 보안 조치 결과 조회 · #212 개정: 시연 결제 금융 조치를 처리 대기 → 다시 확인 완료로 · 2026-10-29 #297 개정: #224 건수 API와 P4 보고서 실제 연동(#250 · #253) 반영 · #309 개정: 결제 ID → Case 조회 · #314 개정: 조치 내역 조회)
- **관련:** Issue #98 · #107 · #160 · #161 · #163 · #205 · #224 · #250 · #297 · #309 · #314, ADR-0020, ADR-0026, ADR-0027, ADR-0034, PR #103 · #113, ADR-0009, Notion FDS 정본 02 §2.5 · 03 §3.5, Notion "목업 데이터 · 연동 현황"
- **영향 받는 담당:** P3 수민 (소유), P1 주환 (frontend · identity), P2 정후 (평가 근거), P4 현규 (보고서), P5 시은 (결제 정보 · 금융 조치 결과)

## 배경

보안 직원 Case 화면(#107)이 목업 대신 `/api/security-cases`를 쓰게 되면서, 화면과 백엔드 사이에 HTTP 계약이 생겼다.
이 API는 P5 결제 정보 · P2 FDS 평가 · P4 보고서를 합쳐 보여 준다. 처음에는 셋 다 P3 목업(`MockPaymentInfoStore` · `MockReportGateway`, 메모리)이었고, #163부터 결제 · FDS는 P5 `PaymentReader`(ADR-0026)와 P2 `FdsEvidenceReader`(ADR-0034)로 조회한다. 보고서는 #250부터 P4 `AiReportService`가 `ai_reports`에 저장한다(ADR-0009). ai-server를 켜지 않으면 같은 자리에서 고정 문장 보고서를 만든다.
목업이던 때는 백엔드를 다시 띄우면 Case · 판정 · 인계는 DB에 남지만 목 결제 정보와 보고서는 사라졌다(지금은 시연 결제의 목 결제 정보만 사라진다). 이때 일부 값만으로 합계를 내거나, 결제 0건 보고서를 성공으로 내보내면 직원이 잘못된 근거로 판정할 수 있다(#113 리뷰).
금융 조치 인계는 #161부터 목업이 아니라 P5 `PaymentActionRequester` · `PaymentActionResult`(ADR-0027)를 쓴다. P3 → P4 보고서 요청 · 조회는 P3의 `ReportGateway` 경계를 P4가 구현한다(ADR-0009, #250). P1 로그인 사용자 조회 공개 경계는 ADR-0020(`identity.api.CurrentStaff`)으로 생겼고, 판정 직원 확인에 쓴다(#160).

## 결정

P3는 아래 계약을 `investigation` 모듈의 HTTP API로 둔다. 다른 모듈 데이터는 조회하지 못하면 추정하지 않고 `null`로 내보낸다.

| 메서드 · 경로 | 용도 |
| --- | --- |
| `GET /api/security-cases?merchantId=` | Case 목록 (`merchantId`는 표시 필터, 인가 아님) |
| `GET /api/security-cases/counts` | 상태별 Case 건수 `{ statusCounts: { OPEN: n, … } }` — 사이드바 처리 필요 배지 (#224) |
| `GET /api/security-cases/payment-links?paymentIds=` | 결제 ID(1~100개) → 연결된 Case `[{ paymentId, caseId, caseStatus }]` — 경보 화면에서 Case로 이동 (#309) |
| `GET /api/security-cases/handoffs` | 조치 내역 — 판정한 결제마다 인계 한 줄(판정 · 조치 · 상태 · 실패 코드 · 금액 · 판정 직원 · 시각), 최근 요청 순 (#314) |
| `GET /api/security-cases/{caseId}` | Case 상세 — 결제 · 보고서 전 버전 · 판정 · 인계 · 이력 |
| `POST /api/security-cases/{caseId}/decisions` | 결제별 ACCEPT · REJECT 판정, 반영된 상세 반환 |
| `POST /api/security-cases/{caseId}/reports` | 현재 증거 버전 보고서가 없을 때만 다시 요청, 반영된 상세 반환 |
| `POST /api/security-cases/{caseId}/handoffs/{handoffId}/recheck` | 금융 처리 대기 중인 인계를 같은 멱등키로 다시 확인, 반영된 상세 반환 (#161) |
| `GET /api/merchant/security-cases` | 고객사 "보안 조치 결과" 목록 — 로그인한 사장님 가맹점의 Case와 요약 지표 (#205) |
| `GET /api/merchant/security-cases/{caseId}` | 고객사 "보안 조치 결과" 상세 — 핵심 요약 · 결제별 판정 · AI 결론 · 결제 상태 (#205) |
| `POST /api/demo/held-payments` | 시연 전용 50~79점 HELD 결제 (P5 결제 입력이 켜지면 #163에서 삭제) |

- 결제 · FDS 출처(#163): `amount` · `paymentStatus` · `requestedAt`은 P5 `PaymentReader`, `riskScore` · `fdsResult` · `ruleHits`는 P2 `FdsEvidenceReader`(결제의 최신 유효 평가)에서 온다. Case 하나의 결제를 한 번에 조회한다(P2는 결제 ID 목록으로 한 번).
- 결제 정보 누락: 한쪽을 못 찾으면 그쪽 값만 `null`이다. P5에 결제가 없으면 `amount` · `paymentStatus` · `requestedAt`이, P2 평가가 없으면 `riskScore` · `fdsResult`가 `null`이고 `ruleHits`는 `[]`다. 평가가 모두 `ERROR`면 `riskScore` `null` · `fdsResult` `ERROR` · `ruleHits` `[]`다.
  목록의 `totalAmount` · `maxRiskScore`는 연결 결제 중 하나라도 값을 모르면 `null`이다. 일부만 더하거나 남은 결제 최댓값을 내보내지 않는다.
- 조회 오류(DB 오류 등)는 `null`로 숨기지 않고 그대로 전파한다(500). `null`은 "없음"일 때만 쓴다.
- `ruleHits`는 `[{ code, name, appliedScore }]`다(#163 이전에는 코드 문자열 목록). 경보 상세 화면과 같은 정보를 보여 준다. 보고서 본문(`reportBody`) 안의 `ruleHits`는 P4 계약 영역이라 코드 문자열 목록 그대로다.
- 보고서 재요청: 최신 보고서의 `evidenceVersion`이 현재 Case보다 낮거나 보고서가 없을 때만 요청한다. 이미 있으면 새로 만들지 않고, 종료된 Case는 그대로 둔다. 요청은 외부 호출이라 트랜잭션 밖에서 한다.
- 상태별 건수(#224): 모든 `CaseStatus`를 키로 보내고 Case가 없는 상태는 `0`이다. 목록과 같은 보안 직원 확인(401 · 403)을 한다. 화면은 화면 이동 · 탭 복귀 · 판정 · 다시 확인 뒤에만 부른다. 시간 간격으로 부르면 요청마다 세션이 연장되어 무활동 만료(ADR-0014 세션 인증)가 오지 않기 때문이다.
- 결제 ID → Case(#309): P2 경보 화면이 Case로 이동할 때 쓴다. P2는 P3를 호출할 수 없고(`fds`는 `identity :: api`만 허용), 경보 이벤트로 Case를 만드는 안은 ADR-0001에서 버렸으므로 화면이 이 API를 부른다. Case에 연결된 결제만 결제 ID 순으로 보내고, Case가 없는 결제(ALLOW · 승인 거절 · DENY 등)는 오류 없이 뺀다. 결제 ID가 없거나 100개(경보 목록 한 페이지)를 넘으면 `INV_015` 400이다. 목록과 같은 보안 직원 확인(401 · 403)을 한다.
- 조치 내역(#314): 인계 하나가 한 줄이다. P3 테이블(`action_handoffs` ⨝ `payment_decisions` ⨝ `case_payments` ⨝ `security_cases`)을 한 번에 읽고, 금액만 `PaymentInfoReader`로 한꺼번에 붙인다(못 찾으면 `null`, 조회 오류는 500). 상태는 완료 기반 인계 그대로(REQUESTED · ACCEPTED · FAILED)이고, `acceptedAt`은 P3 반영 시각이다(실제 완료 시각은 이력). 상태별 탭 · 건수는 화면이 거른다 — 인계는 판정한 결제 수만큼이고 Case 목록과 같은 방식이다. 조회만 하고 조치 실행 · 재요청은 하지 않는다. 목록과 같은 보안 직원 확인(401 · 403)을 한다.
- 고정 문장 보고서(ai-server를 켜지 않을 때, P4 `FixedReportGenerator`. #250 전에는 P3 목 보고서): 연결 결제 정보를 하나라도 찾지 못하거나 금액 · 요청 시각 · 점수 중 하나라도 비면 `SUCCEEDED`가 아니라 `FAILED`(본문 `null`)로 남긴다. 직원은 정본 02 §2.5의 수동 판정(`reportId: null`)으로 간다.
- 판정 직원은 요청 본문으로 받지 않는다. 서버가 `CurrentStaff`로 로그인 사용자를 확인해 `staff_id`로 저장한다. 본문에 `staffId`가 있어도 무시한다(#160).
- 판정은 `SECURITY_STAFF`만 할 수 있다(정본 02 §2.8 · §2.12). 로그인하지 않았으면 `INV_010` 401, 다른 역할(OPERATOR · ADMIN)이면 `INV_009` 403이다. ADMIN 허용이 필요해지면 허용 역할 조건만 바꾼다.
- Case 목록 · 상세 · 보고서 재요청도 `SECURITY_STAFF`만 쓴다(#163, PR #198 리뷰). 실제 P5 결제 · P2 평가 근거를 내보내므로 HTTP 조회 경계(컨트롤러)에서 같은 401 · 403으로 막는다. 보안 직원은 모든 가맹점 Case를 보므로 가맹점 범위는 검사하지 않고, `merchantId`는 표시 필터다. 사장님 화면은 아래 고객사 조회 API를 따로 쓴다(#205). 시연 API는 실제 데이터를 내보내지 않아 그대로 두고 #163 2단계에서 지운다.
- 고객사 조회(#205, 정본 02 §2.12 ②): 사장님(`OPERATOR`) 중 `FDS_VIEW` 권한과 `merchantId`가 있는 계정만 쓴다. 비로그인은 `INV_010` 401, 그 밖(보안 직원 · ADMIN · 권한 없음 · `merchantId` 없는 사장님)은 `INV_014` 403이다. `merchantId` 없는 사장님은 전체로 넓히지 않고 거부한다(ADR-0020).
  - 가맹점은 요청 값이 아니라 세션 `merchantId`로만 정한다. 다른 가맹점 Case 상세는 없는 Case와 같은 `INV_001` 404다. P5 `PaymentReader`는 범위를 검사하지 않으므로 가맹점을 확인한 Case의 결제 ID로만 조회한다(ADR-0026).
  - 표시 상태 `displayStatus`: `OPEN` · `INVESTIGATING` · `DECISION_PENDING` → `INVESTIGATING`(조사 중), `ACTION_PENDING` · `ACTION_FAILED` → `PROCESSING`(판정 완료 · 처리 진행 중), `CLOSED` → `CLOSED`(조사 종료).
  - 목록 지표는 조사 중 Case 수와 가맹점의 ACCEPT · REJECT 판정 건수다. FDS 차단(DENY) 건수는 Case가 없는 P2 평가라 넣지 않는다.
  - 상세는 최신 보고서가 현재 Case 증거 버전을 조사해 `SUCCEEDED`일 때만 `summary`(핵심 요약)와 결제별 `aiRecommendation`을 보낸다. 병합 뒤 새 보고서가 아직 없거나 실패했으면 이전 버전 요약을 내보내지 않는다(PR #207 리뷰). 보고서 없이 판정한 결제가 있으면 `directInvestigation: true`("보안 담당자 직접 조사").
  - 결제별로 직원 판정(`decision`)과 P5 결제 상태(`paymentStatus`)를 따로 보낸다.
  - 보내지 않는 것: 적중 규칙 · 증거 추적 · 판정 사유(정본 §2.12), 판정 직원 · 내부 ID · 인계 실패 코드 · FDS 점수 · 고객 참조(최소 공개).
- 인계 상태는 완료 기반이다(ADR-0027). `REQUESTED`는 금융 처리 대기 중, `ACCEPTED`는 실제 매입 · 취소 완료, `FAILED`는 확정 거절이다. 모든 인계가 `ACCEPTED`가 되어야 Case가 `CLOSED`가 된다.
  - P5 응답 `PENDING`은 정상 대기다. 인계 `REQUESTED` · Case `ACTION_PENDING`을 두고 예외 · 자동 재요청을 하지 않는다.
  - 접수 단계 거절 6개(`PAYMENT_NOT_FOUND` · `PAYMENT_NOT_HELD` · `PAYMENT_NOT_AUTHORIZED` · `IDEMPOTENCY_KEY_CONFLICT` · `PAYMENT_ACTION_CONFLICT` · `DECISION_ACTION_CONFLICT`)는 응답으로만 오므로 응답에서 반영한다. 충돌 3개는 P3 버그 신호라 오류 로그를 남긴다.
  - 그 밖의 완료 결과(매입 완료 · 취소 완료 · `VOID_DECLINED`)는 결과 이벤트로만 반영한다. 응답에는 `completedAt`이 없어서, 응답을 먼저 반영하면 실제 완료 시각이 이력에 남지 않는다.
  - 결과 이벤트는 멱등키로 인계를 찾고 `paymentId` · `decisionId` · `actionType`을 대조한다. 다르거나, 완료된 인계에 다른 `targetOperationId` · 거절이 오면 반영하지 않고 예외로 끝내 `EVENT_PUBLICATION`에 미완료로 남긴다(P5와 대조할 기록). 같은 결과가 다시 오면 무시한다.
  - `acceptedAt`은 P3 반영 시각이다. P5 `completedAt`은 `case_histories.event_data`에 남긴다.
  - 다시 확인이 이벤트보다 먼저 같은 결과를 반영하면 그 이력에는 `completedAt`이 없다. 뒤에 온 결과 이벤트는 상태를 바꾸지 않고, 이 인계 이력에 `completedAt`이 아직 없을 때만 `HANDOFF_COMPLETION_RECORDED`(handoffId · completedAt)를 한 번 남긴다(PR #194 리뷰).
- 다시 확인: `REQUESTED` 인계만 대상이다. 보안 직원만 부를 수 있고(판정과 같은 확인), 같은 멱등키로 P5에 저장된 결과를 받아 반영한다. P6 매입을 다시 실행하지 않고 새 키도 만들지 않는다. 계속 `PENDING`이면 상태는 그대로 두고 `last_attempted_at`과 `HANDOFF_RECHECKED` 이력(확인한 직원 · 결과)만 남긴다. 시간이 지났다고 `FAILED` · `CLOSED`로 바꾸지 않는다(#158).
- 시연 결제는 P5 `payments`에 없다. 목 저장소(`MockPaymentInfoStore`)에 등록된 결제 ID만 목 조회 · 목 금융 조치로 보내고, 그 밖의 결제는 실제 P5 · P2로 보낸다(#163). 목 금융 조치는 실제 P5와 같은 순서로 보인다(#212): 판정 직후 첫 요청은 `PENDING`(인계 `REQUESTED` · Case `ACTION_PENDING`), 직원의 "인계 상태 다시 확인"(같은 멱등키 재요청)에서 완료되고 목 결제 상태를 CAPTURE → `CAPTURED`, VOID → `VOIDED`로 바꾼다. 시간이 지났다고 스스로 완료하지 않는다. 설정값이 없어서 실제 결제가 목으로 "완료"될 수 없다. #161의 `investigation.mock.payment-action-enabled` 속성은 없앴다. 시연 결제의 룰 이름 · 점수는 시연용 값이다.
- 오류는 `code`가 있는 problem 응답이다: `INV_001` · `INV_011` 404, `INV_002` · `INV_003` · `INV_004` · `INV_007` · `INV_012` 409, `INV_005` · `INV_006` · `INV_008` · `INV_015` 400, `INV_009` · `INV_014` 403, `INV_010` 401, `INV_013` 503. 화면은 `INV_003` · `INV_004`를 "다시 확인 후 판정"으로 처리한다.

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
| (#161) 목 금융 조치를 바로 지우기 | 시연 결제가 모두 `PAYMENT_NOT_FOUND`로 실패한다. P5 결제 입력이 켜질 때까지 남긴다 |
| (#212) 시연 결제 금융 조치를 계속 바로 완료 | 결제 상태가 `HELD`인 채로 Case가 `CLOSED`가 되어 "실제 매입 · 취소 완료 뒤 종료"와 반대로 보이고, "금융 처리 대기 중" · 다시 확인 장면을 시연할 수 없다 |
| (#212) 판정 뒤 몇 초 지나면 목업이 스스로 완료 | 정본 02 §2.9 "시간만으로 상태를 바꾸지 않음"과 어긋나고, 백그라운드 타이머 때문에 테스트가 시간에 의존한다 |
| (#163) 시연 모드 속성 하나로 목 조회 · 목 금융 조치를 함께 켜고 끄기 | 운영에서 켜 두면 실제 결제를 판정해도 목이 "완료"를 돌려줘 매입 없이 Case가 종료된다. 결제 ID로 나누면 켤 스위치가 없다 |
| (#163) 조회 오류도 `null`로 내보내기 | 장애가 "값 모름"으로 보여 직원이 빈 근거로 판정할 수 있다 |
| (#163) Case 조회 권한 검사를 P1 전체 접근 제어(#68)까지 미루기 | 목업 값일 때는 미검증으로 두었지만, 실제 금융 데이터를 내보내기 시작하면 비로그인 사용자도 볼 수 있게 된다 |
| (#163) `ruleHits`를 코드 문자열로 유지 | 지금 고칠 곳은 적지만, 경보 화면(이름 · 점수)과 맞추려면 응답 · 화면을 한 번 더 바꿔야 한다 |
| (#205) 보안 직원 목록 API에 `merchantId`를 세션 값으로 넣어 재사용 | 응답에 적중 규칙 · 점수 · 판정 사유 · 이력이 들어 있어 사장님에게 내보내면 안 되는 값을 매번 빼야 한다. 빠뜨리면 그대로 노출된다 |
| (#205) 다른 가맹점 Case에 403 | Case ID를 바꿔 넣어 다른 가맹점 Case가 있는지 알 수 있다. 없는 Case와 같은 404로 답한다 |
| (#205) ADMIN도 고객사 조회 허용 | 관리자는 관리자 화면을 쓰고 `merchantId`가 없다. 필요해지면 허용 조건만 바꾼다 |
| (#309) P2가 경보 생성 · Case 연결 이벤트를 받아 Case ID를 저장 | P2 테이블 변경(ERD 합의)과 이벤트 멱등 처리가 필요하다. 화면에서 Case로 이동하는 목적이면 조회 API로 충분하다 |
| (#309) 결제 ID 하나씩 조회하고 없으면 404 | 대부분의 경보는 Case가 없어 404가 오류로 쌓이고, 경보 목록은 결제마다 요청을 보내야 한다 |
| (#314) 조치 내역을 프로토타입처럼 요청 → 접수 → 완료 3단계로 표시 | 완료 기반 인계(ADR-0027)에는 "접수" 단계가 따로 없고, 확정 거절(FAILED)을 나타낼 단계가 없다 |
| (#314) 서버에서 상태로 거르기(`status=`) | 화면이 탭 건수를 함께 보여야 해 어차피 전체가 필요하고, 컨트롤러가 엔티티 enum을 직접 받게 된다 |
| (#160) ADMIN도 판정 허용 | 정본의 판정 주체는 보안 직원이고 화면에도 ADMIN 진입 메뉴가 없다. 열어 두었다가 나중에 막으면 그 사이 판정을 다시 검토해야 하므로, 막아 두고 필요할 때 연다 |

## 영향

- API: 이 문서의 표. 소비자는 이 저장소 frontend뿐이다. `totalAmount`는 #103의 `long`에서 nullable로 바뀌었다(병합 전 #113에서 변경, 다른 소비자 없음).
- DB: 없음. 목 결제 정보는 메모리다. 보고서는 P4가 `ai_reports`(P4 소유)에 저장하고, P3는 `ReportGateway`로만 조회한다. 이력 종류 `HANDOFF_RECHECKED` · `HANDOFF_COMPLETION_RECORDED`는 `event_type` VARCHAR 값만 추가한다.
- 화면: 고객사 `/owner/actions` 목록 · 상세가 `src/api/merchantCases.ts`로 고객사 조회 API를 쓴다(#205). `src/api/cases.ts`가 숫자 ID를 문자열로 바꾸고, `null`은 `-`로 표시한다. 현재 버전 보고서가 없으면 "AI 보고서 다시 요청" 버튼을 보인다. 인계가 `REQUESTED`면 "금융 처리 대기 중"과 "인계 상태 다시 확인" 버튼, `FAILED`면 거절 코드 문구를 보이고, 이력에 실제 완료 시각을 표시한다.
- 테스트: `CaseFlowTests`(목록 `null` 합계 · 최고 점수, 재요청 1회 · FAILED, 시연 결제의 목 조회 · 목 금융 조치 처리 대기 → 다시 확인 → 종료), `MockFinancialActionGatewayTests`(처리 중 · 완료 · 결제 상태 · 멱등), `HandoffReceiptTests`(결과 이벤트 · 다시 확인), `PaymentActionFlowTests`(실제 P5 · P6 연결), `PaymentFdsInfoReaderTests`(한쪽만 있음 · ERROR · 시연/실제 혼합 · 조회 오류), `PaymentFdsInfoFlowTests`(실제 P5 결제 · P2 평가), `MerchantCaseFlowTests`(고객사 조회 가맹점 범위 · 권한 · 제외 항목 · 직접 조사 표시).
- 문서: 보고서 재요청과 FAILED 의미는 P4 구현(#250 · #253) 뒤에도 위 결정과 같다. 보고서 생성 · 재시도 세부는 ADR-0009를 본다. `staffId` 요청값은 #160에서 없앴다. 시연 `merchantId: 1` 가정은 #163에서 없앤다.

## 검증

- `./gradlew spotlessApply check mariaDbTest` — PR #113 커밋 기준 결과는 PR 본문 "테스트 결과"에 적는다
- `npm run lint && npm run format:check && npm run build` — 같음
- 미검증: Case API는 역할만 확인하고 가맹점 범위는 확인하지 않는다(보안 직원 전용이라 필요 없음). 전체 API 접근 제어(`SecurityConfig`)는 P1 EPIC #68 범위다. 보고서는 이 ADR 작성 당시 목업 기준으로 확인했고, P4 실제 연동(#250 · #253)의 검증은 그 PR들을 따른다. P5 · P2 실제 조회는 테스트에서 P5 결제를 저장소에 직접 만들어 확인했다(P5 결제 입구 #151이 꺼져 있음). P2 평가 값은 룰 설정에 따라 달라서 P2 조회 결과와 같은지만 확인했다
- #160: 판정 권한 테스트는 `CurrentStaff`를 대역으로 바꿔 확인했다. 실제 로그인 세션 → `CurrentStaff` 연결은 P1 테스트(ADR-0020)를 따른다
