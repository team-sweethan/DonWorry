# 0019. P3 Case 조회 · 판정 HTTP 계약

- **상태:** 제안
- **날짜:** 2026-10-23 (2026-10-26 #160 개정: 판정 직원을 로그인 세션으로)
- **관련:** Issue #98 · #107 · #160, ADR-0020, PR #103 · #113, ADR-0009, Notion FDS 정본 02 §2.5 · 03 §3.5, Notion "목업 데이터 · 연동 현황"
- **영향 받는 담당:** P3 수민 (소유), P1 주환 (frontend · identity), P4 현규 (보고서)

## 배경

보안 직원 Case 화면(#107)이 목업 대신 `/api/security-cases`를 쓰게 되면서, 화면과 백엔드 사이에 HTTP 계약이 생겼다.
이 API는 P5 결제 정보 · P2 FDS 평가 · P4 보고서를 합쳐 보여 주는데, 지금은 셋 다 P3 목업(`MockPaymentInfoStore` · `MockReportGateway`, 메모리)이다.
백엔드를 다시 띄우면 Case · 판정 · 인계는 DB에 남지만 목 결제 정보와 보고서는 사라진다. 이때 일부 값만으로 합계를 내거나, 결제 0건 보고서를 성공으로 내보내면 직원이 잘못된 근거로 판정할 수 있다(#113 리뷰).
P4 보고서 요청 계약(ADR-0009)은 아직 없다. P1 로그인 사용자 조회 공개 경계는 ADR-0020(`identity.api.CurrentStaff`)으로 생겼고, 판정 직원 확인에 쓴다(#160).

## 결정

P3는 아래 계약을 `investigation` 모듈의 HTTP API로 둔다. 다른 모듈 데이터는 조회하지 못하면 추정하지 않고 `null`로 내보낸다.

| 메서드 · 경로 | 용도 |
| --- | --- |
| `GET /api/security-cases?merchantId=` | Case 목록 (`merchantId`는 표시 필터, 인가 아님) |
| `GET /api/security-cases/{caseId}` | Case 상세 — 결제 · 보고서 전 버전 · 판정 · 인계 · 이력 |
| `POST /api/security-cases/{caseId}/decisions` | 결제별 ACCEPT · REJECT 판정, 반영된 상세 반환 |
| `POST /api/security-cases/{caseId}/reports` | 현재 증거 버전 보고서가 없을 때만 다시 요청, 반영된 상세 반환 |
| `POST /api/demo/held-payments` | 시연 전용 50~79점 HELD 결제 (P5 `PaymentHeld` 연동 후 삭제) |

- 결제 정보 누락: 상세 결제의 `amount` · `paymentStatus` · `requestedAt` · `riskScore` · `fdsResult`는 `null`, `ruleHits`는 `[]`.
  목록의 `totalAmount` · `maxRiskScore`는 연결 결제 중 하나라도 값을 모르면 `null`이다. 일부만 더하거나 남은 결제 최댓값을 내보내지 않는다.
- 보고서 재요청: 최신 보고서의 `evidenceVersion`이 현재 Case보다 낮거나 보고서가 없을 때만 요청한다. 이미 있으면 새로 만들지 않고, 종료된 Case는 그대로 둔다. 요청은 외부 호출이라 트랜잭션 밖에서 한다.
- 목 보고서: 연결 결제 정보를 하나라도 찾지 못하면 `SUCCEEDED`가 아니라 `FAILED`(본문 `null`)로 남긴다. 직원은 정본 02 §2.5의 수동 판정(`reportId: null`)으로 간다.
- 판정 직원은 요청 본문으로 받지 않는다. 서버가 `CurrentStaff`로 로그인 사용자를 확인해 `staff_id`로 저장한다. 본문에 `staffId`가 있어도 무시한다(#160).
- 판정은 `SECURITY_STAFF`만 할 수 있다(정본 02 §2.8 · §2.12). 로그인하지 않았으면 `INV_010` 401, 다른 역할(OPERATOR · ADMIN)이면 `INV_009` 403이다. ADMIN 허용이 필요해지면 허용 역할 조건만 바꾼다.
- 오류는 `code`가 있는 problem 응답이다: `INV_001` 404, `INV_002` · `INV_003` · `INV_004` · `INV_007` 409, `INV_005` · `INV_006` · `INV_008` 400, `INV_009` 403, `INV_010` 401. 화면은 `INV_003` · `INV_004`를 "다시 확인 후 판정"으로 처리한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 누락 금액을 0으로 더해 숫자 계약 유지 | 고액 Case가 0원으로 보여 우선순위를 잘못 정한다 |
| 숫자 합계 + `incomplete` 플래그 | 화면이 플래그를 놓치면 같은 오인이 생긴다. 모르는 값은 `null` 하나로 표현하는 편이 안전하다 |
| 상세 조회 때 목 보고서를 자동으로 다시 만들기 | 조회가 상태를 바꾸고, 낡은 버전 요청 건너뛰기(stale) 규칙과 충돌한다 |
| 결제 정보가 없어도 결제 0건 보고서를 `SUCCEEDED`로 생성 | 증거가 빠진 보고서가 판정 근거로 인정되어 금융 조치까지 인계된다 |
| `staffId`를 화면이 `/auth/me`로 매번 다시 조회 | `/auth/` 401은 세션 만료 처리로 전달되지 않아, 만료 뒤 판정이 계속 실패만 한다 |
| (#160) 화면이 보낸 `staffId`를 계속 믿기 | 다른 사람 ID나 비로그인 상태로 판정을 남길 수 있다. 판정 기록은 금융 조치로 이어지므로 서버가 확인해야 한다 |
| (#160) ADMIN도 판정 허용 | 정본의 판정 주체는 보안 직원이고 화면에도 ADMIN 진입 메뉴가 없다. 열어 두었다가 나중에 막으면 그 사이 판정을 다시 검토해야 하므로, 막아 두고 필요할 때 연다 |

## 영향

- API: 이 문서의 표. 소비자는 이 저장소 frontend뿐이다. `totalAmount`는 #103의 `long`에서 nullable로 바뀌었다(병합 전 #113에서 변경, 다른 소비자 없음).
- DB: 없음. 목업 데이터는 메모리이고 `ai_reports`(P4 소유)는 쓰지 않는다.
- 화면: `src/api/cases.ts`가 숫자 ID를 문자열로 바꾸고, `null`은 `-`로 표시한다. 현재 버전 보고서가 없으면 "AI 보고서 다시 요청" 버튼을 보인다.
- 테스트: `CaseFlowTests`(목록 `null` 합계 · 최고 점수, 재요청 1회 · FAILED), `HandoffReceiptTests`.
- 문서: P4 보고서 계약이 정해지면 보고서 재요청과 FAILED 의미를 그 계약에 맞춰 이 ADR을 개정한다. `staffId` 요청값은 #160에서 없앴다. 시연 `merchantId: 1` 가정은 #163에서 없앤다.

## 검증

- `./gradlew spotlessApply check mariaDbTest` — PR #113 커밋 기준 결과는 PR 본문 "테스트 결과"에 적는다
- `npm run lint && npm run format:check && npm run build` — 같음
- 미검증: 조회 API의 인가(역할 · 가맹점) 없음 — 판정 API만 역할을 확인한다(#160). P4 · P5 실제 연동 전이라 목업 기준으로만 확인
- #160: 판정 권한 테스트는 `CurrentStaff`를 대역으로 바꿔 확인했다. 실제 로그인 세션 → `CurrentStaff` 연결은 P1 테스트(ADR-0020)를 따른다
