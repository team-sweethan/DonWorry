# P3 Investigation — 1차 MVP 문서

P3 `investigation` 모듈이 하는 일과 현재 구현을 한곳에 모은 안내서다. 기준 커밋은 `457fd22`다(#297에서 #205 이후 변경 반영).
규칙의 정본은 코드 · ADR · Notion이다. 이 문서는 요약하고 링크한다. 내용이 어긋나면 코드와 테스트가 맞고, 이 문서를 고친다.

## 1. 한눈에 보기

```text
P5 HELD 결제 ─(PaymentHeld 이벤트)→ Case 생성 · 30분 병합 → P4 AI 보고서
  → 보안 직원 결제별 ACCEPT / REJECT → P5 금융 조치 인계(CAPTURE · VOID)
  → P5 완료 결과(PaymentActionResult 이벤트) → 모든 결제 실제 완료 → Case CLOSED
```

- P3 책임: Case 생성 · 병합, 증거 버전, 결제별 판정, 금융 조치 인계 요청과 완료 반영, Case 상태 · 이력, 보안 직원 · 고객사 조회 API
- P3가 하지 않는 것: 결제 상태 변경(P5), 매입 실행(P6), FDS 점수 계산(P2), 보고서 생성(P4), 로그인 · 권한 관리(P1)
- 소유 테이블: `security_cases` · `case_payments` · `payment_decisions` · `action_handoffs` · `case_histories`. 실제 컬럼은 [migration SQL](../../backend/src/main/resources/db/migration/)과 [ERD](../erd/README.md)를 본다
- 화면: 보안 직원 `/security/home` (#222) · `/security/cases` · `/security/cases/:id`, 고객사 `/owner/actions` · `/owner/actions/:id`. AI 보고서 상세 `/security/cases/:id/report`는 P4 화면이다
  - Case 목록은 조건(상태 · 24시간 초과 · AI 보고서 · 검색어 · 페이지)을 주소 쿼리에 둔다. 상세에 갔다 와도 · 새로고침해도 같은 조건으로 열리고, 홈 · 목록의 건수 카드는 그 조건의 목록으로 간다(#243, `utils/caseListQuery.ts`)
  - 사이드바 "Case 목록"에 처리 필요 건수 배지를 단다. 화면 이동 · 탭 복귀 · 판정 · 다시 확인 뒤에만 다시 세고, 시간 간격으로 조회하지 않는다(무활동 세션 만료를 막지 않게, #224)

## 2. 현재 구현 — 실제 연동과 목업

| 연결 | 상대 | 경계 | 상태 |
| --- | --- | --- | --- |
| HELD 결제 수신 | P5 | `payment.api.PaymentHeld` 이벤트 → `PaymentHeldListener` | 실제 (#159) |
| 결제 금액 · 상태 · 시각 | P5 | `payment.api.PaymentReader` ([ADR-0026](../decisions/0026-payment-read-api.md)) | 실제 (#163 1단계) |
| FDS 점수 · 판정 · 적중 룰 | P2 | `fds.api.FdsEvidenceReader` ([ADR-0034](../decisions/0034-fds-evidence-read-contract.md)) | 실제 (#163 1단계) |
| 금융 조치 요청 · 결과 | P5 | `PaymentActionRequester` 동기 호출, `PaymentActionResult` 이벤트 ([ADR-0027](../decisions/0027-payment-actions.md)) | 실제 (#161) |
| 로그인 사용자 | P1 | `identity.api.CurrentStaff` ([ADR-0020](../decisions/0020-identity-public-read-contracts.md)) | 실제 (#160 · #205) |
| AI 보고서 요청 · 조회 | P4 | `service.ReportGateway` → `report.service.AiReportService` ([ADR-0009](../decisions/0009-ai-investigation-call-structure.md)) | 실제 (#250 · #253) |

AI 보고서는 P4가 `ai_reports`에 버전별로 저장한다. `ai.server.enabled`가 꺼져 있으면(기본) 결제 근거로 고정 문장 보고서(`FixedReportGenerator`)를 만들고, 켜져 있으면 ai-server(Qwen)에 비동기로 맡긴다. 보고서가 끝나면 P4가 Case 상태를 다시 계산한다(`AiReportFinishedListener`). 세부 규칙은 ADR-0009와 P4 코드를 본다.
결제 · FDS 조회는 `PaymentFdsInfoReader`가 합친다. 한쪽을 못 찾으면 그쪽 값만 `null`이고, 조회 오류는 숨기지 않고 그대로 던진다.
모듈 의존은 `payment :: api` · `fds :: api` · `identity :: api`만 허용한다(`package-info.java`, [ADR-0012](../decisions/0012-payment-settlement-dependency.md)). P4(`investigation/report`)는 같은 모듈 안의 하위 패키지다.

## 3. Case 상태와 전이 우선순위

상태 판단은 `InvestigationRules.nextStatus` 하나에서 한다. 결제 연결 · 판정 · 인계 결과 · 보고서 요청 뒤에 `CaseStatusService.refresh`가 이 함수로 다음 상태를 정하고, 바뀌면 `CASE_STATUS_CHANGED`(종료면 `CASE_CLOSED`) 이력을 남긴다.

위에서부터 먼저 맞는 줄이 이긴다.

| 순서 | 조건 | 다음 상태 |
| --- | --- | --- |
| 1 | 이미 `CLOSED` | `CLOSED` 유지 — 종료 뒤의 금융 결과로 다시 열지 않는다 |
| 2 | 인계 중 하나라도 `FAILED` | `ACTION_FAILED` |
| 3 | 모든 결제 판정 + 모든 인계 `ACCEPTED`(실제 매입 · 취소 완료) | `CLOSED` |
| 4 | 모든 결제 판정 (인계 대기 중) | `ACTION_PENDING` |
| 5 | 미판정 결제 있음 + 최신 보고서가 현재 증거 버전보다 낮거나 생성 중 | `INVESTIGATING` |
| 6 | 미판정 결제 있음 + 보고서 없음 | `OPEN` |
| 7 | 미판정 결제 있음 + 현재 증거 버전 보고서 완료(`SUCCEEDED` 또는 `FAILED`) | `DECISION_PENDING` |

- 보고서 `FAILED`도 "완료"로 본다. 직원이 수동 판정(`reportId = null`)할 수 있어야 하기 때문이다(정본 02 §2.5).
- 시간이 지났다고 `FAILED` · `CLOSED`로 바꾸지 않는다. 오래 대기한 인계는 24시간 SLA 경고와 "인계 상태 다시 확인"으로 처리한다(#158).
- 규칙과 테스트는 `FAILED` 인계가 다시 `REQUESTED`가 되면 `ACTION_PENDING`으로 돌아가는 경우도 다룬다. 다만 지금 `FAILED`를 되돌리는 경로는 없다. 판정 정정은 후속 확장이다(정본 02 §2.13).
- 기준 테스트: `InvestigationRulesTests`

## 4. 병합 규칙

HELD 결제가 오면 `CaseIntakeService.receive` → `CaseLinkService.link`가 기존 Case에 붙이거나 새 Case를 만든다(정본 02 §2.4 · 03 §3.4).

기존 Case에 붙는 조건(모두 만족):

1. 같은 `merchant_id`
2. 같은 `customer_ref`
3. 같은 조사 유형 `PAYMENT_FRAUD`
4. 상태가 `OPEN` · `INVESTIGATING` · `DECISION_PENDING` (`MERGEABLE_STATUSES`)
5. Case 최초 생성 후 30분 미만 (`MERGE_WINDOW`, 결제가 붙어도 연장하지 않음)
6. 판정이 하나도 없음

- 1~6은 `SecurityCaseRepository.findMergeCandidates` 조회 조건과 `InvestigationRules.canMerge`가 함께 본다. 후보가 여럿이면 가장 최근 Case에 붙는다.
- 병합되면 증거 버전(`evidence_version`)이 1 오르고, 커밋 뒤 새 버전 보고서를 요청한다(`CaseEvidenceChanged`).
- 동시성: 후보를 쓰기 잠금으로 읽어 같은 Case의 증거 버전 증가가 덮어써지지 않게 한다. 같은 고객 결제가 동시에 처음 오면 잠금 충돌 · `UNIQUE(payment_id)` 위반이 날 수 있어 그 트랜잭션만 롤백하고 다시 시도한다(최초 포함 최대 3번). 같은 결제가 다시 오면 이미 연결된 Case를 돌려준다(멱등).
- 판정 중에도 같은 Case 행을 잠가, 판정 사이에 결제가 끼어들어 증거 버전이 바뀌지 않게 한다.

## 5. 판정과 금융 조치 인계

- 판정은 보안 직원(`SECURITY_STAFF`)만 한다. 판정 직원은 요청 본문이 아니라 로그인 세션으로 정한다(#160).
- 보고서 검증(`CaseDecisionService.validateReport`)
  - `reportId`가 있으면: 최신이면서 `SUCCEEDED`이고 현재 증거 버전과 같아야 한다. 아니면 `INV_004`
  - `reportId = null`(수동 판정)은 최신 보고서가 현재 증거 버전의 `FAILED`일 때만 허용한다. 아니면 `INV_005`
  - 화면이 본 증거 버전이 현재와 다르면 `INV_003`
- 판정과 인계 요청(`action_handoffs` `REQUESTED`)은 한 트랜잭션에 저장하고, 커밋 뒤 P5를 부른다. ACCEPT → `CAPTURE`(P6 매입), REJECT → `VOID`(P5 승인 취소).
- 인계는 완료 기반이다(ADR-0027). `REQUESTED` = 금융 처리 대기 중, `ACCEPTED` = 실제 매입 · 취소 완료, `FAILED` = 확정 거절.
  - P5 응답 `PENDING`은 정상 대기다. 접수 단계 거절 6종은 응답으로, 완료 결과와 `VOID_DECLINED`는 결과 이벤트로만 반영한다(실제 완료 시각 `completedAt`을 이력에 남기려고).
  - 결과 이벤트는 멱등키로 인계를 찾고 결제 · 판정 · 조치 유형을 대조한다. 같은 결과가 다시 오면 무시한다.
  - "인계 상태 다시 확인"은 같은 멱등키로 P5에 저장된 결과를 받아 온다. P6 매입을 다시 실행하지 않는다.
- 세부 규칙과 버린 대안은 [ADR-0019](../decisions/0019-security-case-http-contract.md) "결정"절을 본다.

## 6. API

| 경로 | 쓰는 사람 | 용도 |
| --- | --- | --- |
| `GET /api/security-cases` | 보안 직원 | Case 목록 |
| `GET /api/security-cases/counts` | 보안 직원 | 상태별 Case 건수 — 사이드바 처리 필요 배지 (#224) |
| `GET /api/security-cases/{caseId}` | 보안 직원 | Case 상세 (결제 · 보고서 · 판정 · 인계 · 이력) |
| `POST /api/security-cases/{caseId}/decisions` | 보안 직원 | 결제별 판정 |
| `POST /api/security-cases/{caseId}/reports` | 보안 직원 | 현재 버전 보고서가 없을 때 다시 요청 |
| `POST /api/security-cases/{caseId}/handoffs/{handoffId}/recheck` | 보안 직원 | 인계 상태 다시 확인 |
| `GET /api/merchant/security-cases` | 사장님(`FDS_VIEW`) | 내 가맹점 Case 목록 · 요약 지표 (#205) |
| `GET /api/merchant/security-cases/{caseId}` | 사장님(`FDS_VIEW`) | 핵심 요약 · 결제별 판정과 실제 결제 상태 (#205) |
| `POST /api/demo/held-payments` | 시연 | 50~79점 HELD 결제 만들기 (#163 2단계에서 삭제) |

- 오류는 `code`가 있는 problem 응답이다(`INV_001`~`INV_014`). 코드별 의미와 상태는 ADR-0019와 `InvestigationErrorCode`를 본다.
- 고객사 API는 가맹점을 세션으로만 정하고, 다른 가맹점 Case는 `INV_001` 404로 답한다. 적중 규칙 · 점수 · 판정 사유 같은 내부 근거는 보내지 않는다.

## 7. 목업 범위와 지울 조건

| 목업 | 하는 일 | 지울 때 |
| --- | --- | --- |
| `MockPaymentInfoStore` | 시연 결제의 금액 · 점수 · 룰을 메모리에 둔다 | P5 결제 입구(#151)가 켜진 뒤 #163 2단계 |
| `MockFinancialActionGateway` | 시연 결제의 금융 조치. 첫 요청은 처리 중(`PENDING`), "인계 상태 다시 확인"에서 완료되고 결제 상태를 `CAPTURED` · `VOIDED`로 바꾼다 (#212) | 같음 (#163 2단계) |
| `DemoHeldPaymentController` · `DemoHeldPaymentService` | 시연 HELD 결제 API | 같음 (#163 2단계) |

- 시연 결제와 실제 결제는 결제 ID로 나뉜다. 목 저장소에 등록된 ID만 목업으로 가고, 나머지는 실제 P5 · P2로 간다. 켜고 끄는 설정값은 없다.
- 서버를 다시 띄우면 Case · 판정 · 인계 · 보고서는 DB에 남지만 시연 결제의 목 결제 정보는 사라진다. 이때 시연 결제 Case는 금액 · 점수가 `null`로 보인다.
- AI 보고서는 목업이 아니다(#250에서 `MockReportGateway`를 P4 `AiReportService`로 바꿨다). ai-server를 켜지 않은 로컬의 고정 문장 보고서도 실제 결제 근거로 만들고 DB에 저장한다.
- 담당 전체의 목업 현황은 Notion [목업 데이터 · 연동 현황](https://www.notion.so/401ee45ba5e181a7b9f0ee51e9578999)을 본다.

## 8. 근거 문서

- 업무 이유: Notion [FDS 정본](https://www.notion.so/40102d70288a80bf8245c3fb8ddb231c) · [02 기획](https://www.notion.so/05002d70288a823f942c01fd7debd526) §2.3~§2.10 · §2.12 · [03 설계](https://www.notion.so/e6702d70288a82ef812101c4f8dd1e08) §3.3~§3.5 · §3.8 · §3.9
- 결정: [ADR-0019](../decisions/0019-security-case-http-contract.md) Case 조회 · 판정 HTTP 계약, [ADR-0027](../decisions/0027-payment-actions.md) 완료 기반 인계, [ADR-0009](../decisions/0009-ai-investigation-call-structure.md) AI 보고서, [ADR-0012](../decisions/0012-payment-settlement-dependency.md) 모듈 의존, [ADR-0020](../decisions/0020-identity-public-read-contracts.md) · [ADR-0026](../decisions/0026-payment-read-api.md) · [ADR-0034](../decisions/0034-fds-evidence-read-contract.md) 공개 조회 경계
- 테스트: `InvestigationRulesTests`(상태 · 병합 규칙), `CaseFlowTests`(생성 · 병합 · 판정 · 종료 HTTP), `HandoffReceiptTests` · `PaymentActionFlowTests`(인계), `PaymentFdsInfoReaderTests` · `PaymentFdsInfoFlowTests`(결제 · FDS 조회), `MerchantCaseFlowTests`(고객사 조회)
- Notion과 저장소의 남은 차이: 열린 `notion-sync` 라벨 이슈를 본다(2026-10-29 기준 없음. 완료 기반 인계 동기화 #174는 닫힘)
