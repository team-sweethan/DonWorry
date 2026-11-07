# 0082. 보안 직원 고객정보 접근 보안 — 모듈 소유와 탐지 흐름

- **상태:** 제안 — 실험(#536, `spike/insider-on-dev`)으로 흐름 검증, 팀 합의 필요
- **날짜:** 2026-11-06
- **관련:** #600 (이 ADR) · #604 (허용 기록 트랜잭션) · #643 (합의 3 · INS-07 · 08 · 09), #536 (실험), #516 (P2 내부자 탐지 — 범위 재정리 대상), #515 · [ADR-0078](0078-operator-account-suspension.md) · [ADR-0079](0079-operator-security-contracts.md) · [ADR-0080](0080-operator-access-denials.md) (사장님 403 탐지), [ADR-0009](0009-ai-investigation-call-structure.md)
- **영향 받는 담당:** P1 identity, P2 fds, P3 investigation, P4 investigation/report, P5 payment, frontend

## 배경

보안 직원(SECURITY_STAFF)은 업무상 모든 가맹점의 결제를 볼 수 있다. 실험에서는 결제 준비 요청의 주문자 이름 · 전화도 받았다.
"권한은 있지만 업무와 관계없는 조회"(무관 고객 반복 검색 · 대량 조회 · 원문 몰아보기 · 경보 중 반출)는 지금 기록도 탐지도 하지 않는다.

- 사례: 2014년 카드 3사 유출은 FDS 프로젝트 외부 직원이 고객 이름 · 전화 · 이용명세를 반출한 사건이다
- 돈워리는 카드번호를 저장하지 않는다(토큰). 내부자가 가져갈 수 있는 가장 민감한 정보는 주문자 연락처 + 결제 내역이다

#536 실험 브랜치에서 결제 준비 → 사건 배정 → 열람 → 기록 → 경보 → 차단 → 내부자 사건 → 보고서 → 관리자 판정을 로컬에서 끝까지 돌렸다.
이 ADR은 실험 코드를 정식 PR로 나눠 넣기 전에 **어느 모듈이 무엇을 갖고, 모듈 사이를 어떻게 잇는지**를 정한다.
각 조각의 세부(칸 · 기준값 · 화면)는 조각 PR에서 정한다.

이미 dev에 있는 것과의 관계:

- #515 · ADR-0078~0080은 **사장님(OPERATOR)**의 권한 없는 API 반복 호출(403)을 탐지한다. 이 ADR은 **보안 직원**의 권한 안 조회를 다룬다. 대상 · 신호 · 조치가 달라 표와 흐름을 따로 둔다
- #516은 접근 기록과 경보를 모두 P2에 두는 설계였다. 실험은 접근 기록을 P1에 두었다 (아래 결정 2)

## 결정

보안 직원 접근 보안은 기존 FDS 결제 룰(R01~R08)과 사장님 403 탐지와 **별도 체계**로 둔다.
담당 모듈별로 소유를 나누되 **새 모듈 의존은 추가하지 않는다** (현재 `allowedDependencies` 그대로).

### 1. 원칙

- 막기 대신 **쓰게 두고 · 기록하고 · 이상하면 탐지**한다. 업무에 필요한 조회(거래 목록 · 고객 번호 검색)는 유지한다
- 원문(이름 · 전화)은 접근 기록 · 경보 · 보고서 · AI 입력 · 로그 어디에도 복사하지 않는다
- 구매자 이메일은 FDS 차단 목록 대조 때문에 원문으로 저장하지만(`fds_evaluations` · `payment_requests` · `fds_blocklist_entries`), 보안 직원 화면 · API 응답 · 요청 URL · FDS 규칙 근거 · 다른 모듈로 넘기는 근거에는 **가린 값**(`ch***@gmail.com`, P5 `PaymentSignalMasking.email`과 같은 규칙)만 둔다 (#603)
  - 근거: ISMS-P 2.6.3(응용프로그램 접근, 2023-10-31 개정으로 기존 3.2.3 표시제한을 통합)은 같은 개인정보 항목을 화면마다 다르게 가리는 것과, 화면만 가리고 응답(소스 보기)에 원문을 남기는 것을 결함으로 본다
  - 화면이 원문 없이 일하도록 경보 상세 차단 등록은 평가 ID로 받고(`POST /api/fds/evaluations/{evaluationId}/blocklist-entries`) 서버가 평가에서 값을 찾는다. 차단 여부도 상세 응답에 담는다
  - 직원이 [차단 목록]에서 직접 입력해 등록하는 값은 직원이 이미 아는 값이라 그대로 받는다
  - IP · 기기 ID도 같은 자리에서 P5 `PaymentSignalMasking.ipAddress` · `deviceId`와 같은 규칙으로 가린다(`211.234.*.*` · `dev-7f3a…`). 카드 토큰은 가린 값도 내보내지 않고 표시값(`VISA •••• 1234`)만 쓴다 (#628). 근거 키는 `maskedDeviceId` · `maskedIpAddress` · `cardDisplayLabel`이고, 예전 근거의 `deviceId` · `ipAddress` · `cardToken`은 조회할 때 바꾼다
  - 고객 번호(`BUYER_REF`)는 가리지 않는다. 가맹점이 준 내부 번호이고 P5 거래 목록도 그대로 보여 줘 화면 간 기준이 같다. 가릴지는 따로 판단한다 (#628)
  - 원래 IP가 업무에 필요한 경우(수사기관 제출 등)가 생기면 주문자 전화처럼 사유를 남기고 1건만 여는 방식으로 따로 만든다. 차단 목록 부분 일치 검색은 #629에서 다룬다
- 주문자 연락처는 **승인 절차보다 볼 수 있는 범위로 지킨다** (11/07, #615 PR 리뷰 중 판단). 가린 값 · 대조 · 원문 모두 담당 열린 사건에 속한 결제 1건 단위로만 열고, 거래 목록처럼 여러 고객을 한 번에 보는 화면에는 두지 않는다
  - 2014년 사례의 반출자는 정식 투입된 직원이었다. 승인은 한 번 통과하면 그 뒤 범위를 줄이지 못하지만, 범위 제한은 매 조회마다 작동한다
- 판정 · 정지 해제는 ADMIN이 한다. 보고서 생성 실패가 사건 · 정지 상태를 바꾸지 않는다 (AGENTS.md 6절)
- 탐지 기준값은 측정 전 임시값이며 전부 설정값(`donworry.insider.*`)으로 둔다

### 2. 모듈별 소유

| 담당 | 소유하는 표 | 공개 경계 (`api/`) | 역할 |
| --- | --- | --- | --- |
| P5 payment | `payment_order_contacts` | `CustomerContactReader` · `MaskedContact` | 주문자 연락처 암호문(AES-GCM) + 전화 지문(HMAC) 저장, 가린 값 · 대조 · 전화 원문 제공, 거래 내역 CSV 내보내기 |
| P1 identity | `staff_access_events` · `staff_account_locks` | `StaffAccessRecorder` · `StaffAccessReader` · `StaffAccessRecorded`(이벤트) · `StaffLoginReader` · `StaffAccountLocker` · `SecurityStaffDirectory` | 직원 접근 기록(허용 · 거부, 원문 없음), 로그인 세션 묶기, 직원 계정 보안 정지 · 해제 |
| P2 fds | `insider_alerts` · `insider_alert_evidences` · `insider_detection_states` | `InsiderAlertRaised`(이벤트) · `InsiderAlertReader` · `InsiderAlertCloser` · `InsiderAlertEvidenceReader` · `InsiderAlertMonitorReader` · `InsiderExportGuard` · `StaffCaseScopeReader`(질문지) | 접근 기록 수신 → 규칙 판단 → 경보, 경보 중 내보내기 차단 |
| P3 investigation | `security_cases.assignee_staff_id` · `case_assignment_locks` · `case_assignment_requests` · `raw_view_reviews` · `insider_cases` · `insider_case_alerts` | (내부) | FDS 사건 자동 배정 · 담당 변경, 열린 사건은 담당자만, 원문 열람 사후 검토, 내부자 사건 · 관리자 판정, 관제 조회(여러 모듈 데이터를 합치는 위층) |
| P4 investigation/report | `ai_insider_reports` | (내부) | 내부자 사건 해제 검토 보고서 — 사실은 코드가 계산, 서술은 고정 문장(AI 서술은 후속), `report_version` 누적 |

접근 기록을 P1에 두는 이유: 기록 주체가 로그인 세션(`SessionUser.loginReference`)이고, 정지 기록과 같은 계정 경계에 있다.
P1은 다른 모듈을 import하지 않으므로(`allowedDependencies = {}`) payment · investigation이 기록을 남기고 fds가 이벤트로 받는 방향이 화살표와 맞는다.

### 3. 모듈 사이 흐름

```
payment ──(StaffAccessRecorder 호출)──▶ identity ──(StaffAccessRecorded, 커밋 후)──▶ fds
investigation ──(StaffAccessRecorder 호출)──▶ identity
fds ──(StaffAccountLocker · StaffAccessReader · StaffLoginReader)──▶ identity
fds ──(InsiderAlertRaised, 높음 이상, 커밋 후)──▶ investigation ──(모듈 안 알림)──▶ investigation/report
payment ──(InsiderExportGuard 호출, 내보내기 직전 동기 검사)──▶ fds
investigation ──(CustomerContactReader)──▶ payment
investigation ──(InsiderAlertReader · Closer, StaffAccountLocker)──▶ fds · identity
fds ··(StaffCaseScopeReader, investigation이 구현)··▶ investigation   ← 실행 중 역방향 동기 호출
```

- 이벤트 타입은 의존을 받는 쪽 `api/`에 둔다 (AGENTS.md 4절 · ADR-0012): `StaffAccessRecorded` → identity, `InsiderAlertRaised` → fds
- 수신은 멱등이다: 같은 접근 기록은 경보 근거 한 줄(`insider_alert_evidences` UNIQUE), 같은 경보는 내부자 사건 한 줄(`insider_case_alerts` PK), 같은 계기는 보고서 한 판(`uk_ai_insider_reports_trigger`)
- 사람 · 규칙당 열린 경보 1개, 사람당 열린 내부자 사건 1개, 사람당 열린 정지 1개를 생성 칼럼 UNIQUE로 막는다

**`StaffCaseScopeReader` (합의 3 정함, 11/07 · #643):** "이 직원의 담당 사건 범위"는 P3 데이터지만 P2 규칙(INS-08 · INS-09)이 필요로 한다.
fds가 investigation을 import할 수 없어 fds.api에 질문지 인터페이스를 두고 investigation이 구현한다. 컴파일 의존은 화살표 안이지만, 실행 중에는 fds가 investigation 답을 동기로 받는다.
저장소에서 처음 쓰는 방식이다. 모듈을 서비스로 나누게 되면 담당 변경 이벤트 → fds 사본 표로 바꾼다.
담당 범위 = 지금 담당인 열린 사건 + 30일 안에 닫힌 담당 사건의 (가맹점, 고객 번호). 지금 배정 기준이며 지난 시점 배정 이력은 보지 않는다.

### 4. 기록 방식

- 접근 **거부** 기록: 독립 트랜잭션(`REQUIRES_NEW`). 기록이 실패해도 원래 거부 응답은 바꾸지 않는다 (ADR-0080과 같은 방식)
- 접근 **허용** 기록: 호출 쪽과 **같은 트랜잭션**에 저장한다 (#604). 기록 · 업무가 함께 커밋되거나 함께 롤백된다
  - 기록이 실패하면 호출 쪽이 롤백되어 데이터를 내보내지 않는다(fail closed)
  - 기록 메서드는 `Propagation.MANDATORY`로 둔다. 트랜잭션 밖에서 부르면 예외가 나서 빠뜨린 경계를 테스트에서 잡는다
  - 기록을 부르는 조회는 읽기 전용 트랜잭션(`readOnly = true`)을 쓰지 않는다. 읽기 전용이면 기록 INSERT가 실패한다(이 경우도 데이터는 나가지 않는다). 부르는 쪽 변경은 해당 조각 PR에서 한다
  - 이벤트 `StaffAccessRecorded`는 커밋 후 전달된다
  - 커밋 뒤 응답 전송이 실패하면(네트워크 끊김) "기록 있음 · 수신 없음"이 남는다. DB 커밋과 전송은 묶을 수 없어 받아들인다
- 원문 응답(`contact-raw-view`)은 `Cache-Control: no-store`
- IP는 접근 기록에 복사하지 않는다. 필요하면 `login_histories`에서 읽는다

### 5. 직원 계정 보안 정지 (합의 4 정함, 11/07 · #647 → [ADR-0084](0084-staff-account-security-suspension.md))

**정함:** 별도 상태 `SUSPENDED`로 두고 조사 판정으로만 푼다. 해제 결과(복귀 · 비활성)는 판정이 고른다. 아래는 실험 당시 기록이다.

실험은 기존 `UserStatus.INACTIVE`를 재사용하고, 정지 사유 · 근거 경보 · 해제 판정은 `staff_account_locks`에 둔다.
알려진 한계: [계정 관리]의 [활성]으로 판정 없이 풀 수 있다(옆문). 정식안 후보는 별도 `LOCKED` 상태다. 결정은 P1 조각 PR의 별도 ADR로 남긴다.
사장님 정지(`OperatorAccountSuspender`, ADR-0078)는 직원을 거절하므로 직원 정지는 별도 경계로 둔다.

### 6. 시연 데이터

시연용 보안 직원 계정(demoA~F)은 Flyway 마이그레이션에 넣지 않는다. 마이그레이션은 모든 환경에 적용되고 병합 후 고칠 수 없다(AGENTS.md 4절).
로컬 시연 준비 스크립트(`scripts/demo/`)가 관리자 API로 만든다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 실험 브랜치를 dev에 한 번에 병합 | 다른 담당 표 · 모듈 변경을 확인 없이 넣게 됨, 시연 계정 시드가 모든 환경에 들어감, mariaDbTest 기존 테스트 충돌(실험 기준 42~43건) |
| 접근 기록 · 경보 모두 P2 소유 (#516 초안) | 기록 주체인 로그인 세션 · 정지 기록이 P1에 있음. P2에 두면 payment · investigation이 기록하려고 fds를 부르고, 세션 번호를 identity 밖으로 꺼내야 함 |
| 허용 기록도 독립 트랜잭션(`REQUIRES_NEW`)으로 저장 (실험) | 기록 뒤 같은 요청 안에서 오류가 나 데이터가 나가지 않아도 "열람함" 기록이 남는다. 실제보다 많이 기록되면 탐지 건수가 부풀어 오탐이 생기고, 감사 때 "기록 = 실제로 나감"이라고 말할 수 없다. 얻는 것은 부르는 쪽의 `readOnly` 유지뿐이다. "데이터는 나갔는데 기록 없음"은 두 방식 모두 막는다 |
| 기존 `operator_access_denials` 재사용 | 사장님 거부만 담는 표(`merchant_id` 필수). 직원 허용 기록 · 열람 종류 · 건수 칸이 없음 |
| 기존 `security_cases`에 `case_type`으로 내부자 사건 추가 | FDS 사건은 결제 · 판정 · 인계 전제이고 내부자 사건은 직원 · 경보 · 정지 전제라 상태 · 칸 · 화면 권한이 다름. 기존 Case 조회 · 판정 코드가 모두 분기해야 함 |
| 내부자 신호를 FDS 결제 점수에 합침 | 결제 위험과 직원 행동은 대상 · 조치가 다름. 한 점수로 섞으면 기존 룰 결과가 바뀜 |
| 원문 열람을 매번 관리자 사전 승인 | 업무 지연. 위험은 1건 열람이 아니라 업무와 안 맞는 누적이라 사후 검토 + 탐지로 대신함 (실험에서 구현 후 취소) |
| 담당 사건 범위를 fds가 사본 표로 보관 | 담당 변경 이벤트 · 재전달 · 순서 처리가 필요. 같은 앱 안에서는 질문지 동기 호출로 충분 (서비스 분리 시 재검토) |
| 이름 검색 제공 | 고객센터 무단 조회의 대표 경로. 본인 확인은 사건 안 "대조"로 대신함 |
| 승인받으면 고객 연락처 원문을 전체로 열람 (11/07 검토) | 승인 뒤에는 모든 고객이 범위가 되어 대량 반출을 막지 못함. 2014년 사례처럼 정식 권한자의 반출에는 승인이 효과가 없음. 매번 결재를 기다려 업무도 늦어짐. 사건 범위 1건씩 열람 + 사후 검토 + 탐지로 대신함 |
| 거래 목록에 주문자 이름 · 전화 가린 값 칸 추가 (11/07 검토) | 위험은 낮지만(원문 아님, 목록 조회는 #615로 기록) 가린 이름 · 전화로 할 수 있는 업무가 거의 없음. 조회마다 행별 복호화가 필요하고, 사건과 무관한 고객 연락처를 넓게 보이게 됨. 보안 직원의 업무 단위는 사건이라 사건 상세 주문자 정보 카드(조각 5)로 충분함. 고객센터처럼 "이 사람의 모든 거래"가 필요한 역할이 생기면 다시 검토 |
| 픽업자 원문 저장 | 판정 근거로 쓰지 않음(11/03 팀 회의). "주문자와 다름" 표시만 둠 |
| 시연 직원을 마이그레이션 시드로 추가 | 운영 환경에도 비밀번호 1234 계정이 생기고, 병합 후 고칠 수 없음 |
| 이메일을 화면에서만 가림 (응답은 원문 유지, #603 검토) | 차단 등록이 화면이 받은 원문을 다시 보내는 구조라 고치기는 쉽지만, 응답 · 개발자 도구 · URL 쿼리에 원문이 남아 ISMS-P 2.6.3 결함사례("소스보기로 원문 노출")에 그대로 해당함 |
| P5 가림 클래스를 `payment.api`로 공개해 fds가 사용 (#603 검토) | fds → payment 의존이 새로 생겨 `allowedDependencies`와 ADR-0012 개정이 필요함. 규칙이 짧아 fds 안에 복사하고 같은 예시 테스트로 맞춤 |
| IP · 기기 ID는 P2 화면에서 원문 유지, P5 거래 목록을 원문으로 바꿔 기준을 맞춤 (#628 검토) | 화면 간 기준은 같아지지만 ADR-0051(P5 응답에 IP · 기기 원문 없음)을 뒤집어야 하고, 지금 시스템에서 직원이 원문으로 해야 하는 일(차단 · 연결 결제 · 국가 확인 · 검색 · 해제)이 없어 최소 표시 원칙에 어긋남 |
| 카드 토큰을 가린 값(앞 몇 자)으로 표시 (#628 검토) | 토큰은 사람이 봐도 뜻이 없고 표시값이 이미 있다. 가린 토큰은 카드 구분에도 표시값보다 못하다 |
| R07 근거 JSON을 저장할 때만 가림 (#603 검토) | #603 전에 저장한 기록의 원문이 경보 상세 · P3 근거 조회로 계속 나감. 조회할 때도 가리고, 원문 · 가린 값은 키 이름(`buyerEmail` · `maskedBuyerEmail`)으로 구분함 — 가린 값을 다시 가리면 짧은 이메일 모양이 바뀌기 때문(`a***@` → `a****@`) |

## 영향

- API: 새 HTTP 경로 약 30개(ADMIN · SECURITY_STAFF). 조각 PR마다 `SecurityConfig`에 정확한 메서드 · 경로로 추가한다 (AGENTS.md 4절, ADR-0059). 결제 준비 요청(`PaymentSessionCreateRequest`)에 주문자 · 픽업자 칸 추가 — CakeShop 계약 변경
  - #603: FDS 평가 상세의 차단 후보가 `identifierValue` · `displayLabel` → `displayValue` · `blocked`로 바뀜, 평가 ID로 차단 등록하는 경로 추가, [차단 목록] 응답과 R07 근거의 이메일은 가린 값(R07 근거 키는 `maskedBuyerEmail`)
  - #628: FDS 평가 상세의 `cardIdentifier.cardToken` 삭제, 차단 후보 IP · 기기 ID는 가린 값 · 카드는 표시값(없으면 `null`), [차단 목록] 응답 `identifierValue`는 IP · 기기 ID 가린 값 · 카드 `null`, R02 · R03 · R07 근거 키 변경(`maskedDeviceId` · `maskedIpAddress` · `cardDisplayLabel`, `cardToken` 없음). P3 · P4는 이 키를 읽지 않음(P4 `RuleCriterionFormatter`는 R02 · R03 `approvedHistoryCount`만)
- DB: 새 표 12개 + `security_cases` 칸 2개 + `staff_access_events` 칸 2개 + `insider_cases` 칸 4개. 표마다 소유 담당 마이그레이션으로 만들고 [`docs/erd/`](../erd/README.md)에 적는다. 다른 담당 표 사이는 논리 참조만
- 화면: 관리자 메뉴 [사건 담당] · [내부자 보안](경보 · 사건 · 관제 · 원문 열람 검토), 보안 직원 사건 상세(담당 표시 · 주문자 정보 카드 · 담당 변경 요청), 거래 목록 CSV 내보내기. #603: 경보 상세(차단 후보 · R07 근거) · [차단 목록]의 이메일 가림, 경보 상세 차단 등록이 원문 없이 동작. #628: 경보 상세 "카드 식별값" 줄 삭제, 차단 후보 · R02 · R03 · R07 근거 · [차단 목록]의 IP · 기기 ID 가림
- 테스트: 기존 "누구나 판정" 전제 테스트가 "열린 사건은 담당자만"으로 바뀐다. 실험 기준 mariaDbTest 42~43건 실패를 해당 조각 PR에서 고친다. #603: 이메일 가림 규칙을 P5와 같은 예시로 검사(`FdsIdentifierMaskingTests`), 상세 · 차단 목록 · 근거 조회 응답에 원문이 없는지 검사. #628: IP · 기기 ID 규칙도 P5와 같은 예시로 검사, 상세 응답 전체 · 새 근거 저장 · 예전 근거 조회 · 차단 목록 응답에 IP · 기기 ID · 카드 토큰 원문이 없는지 검사
- 설정: `donworry.pii.encryption-key` · `hmac-key`(운영 비밀값), `donworry.insider.*` 기준값
- 문서: #516 범위 재정리, ERD, CakeShop 연동 문서(주문자 칸)

## 검증

실험 브랜치 기준(정식 PR의 통과 근거로 쓰지 않는다):

- `spike/insider-on-dev` 93af5bd: 백엔드 `spotlessApply` · `check` 통과(사용자 PC 11/06 13:09), 프론트 lint · format · build · test 380 통과
- 로컬 리허설(11/06 10:1x~10:35): 시연 장면 ①~⑥ 통과
- 미검증: mariaDbTest(실험 기준 42~43건 실패 기록), 비동기 이벤트 실제 전달의 동시성, 요청 롤백 뒤 정지 유지, 운영 키 관리

정식 검증은 조각 PR마다 `./gradlew spotlessApply && ./gradlew check && ./gradlew mariaDbTest`와 프론트 검사 결과로 적는다.

## 합의가 필요한 것

번호는 조각 이슈에서 참조하므로 정해진 항목도 번호를 유지하고 "정함"으로 표시한다. 모두 정해지면 이 절을 지우고 상태를 "채택"으로 바꾼다.

1. ~~접근 기록 담당~~ — **정함 (11/06, #604)**: P1 identity (2 · 3절 그대로). #516은 탐지 · 경보만
2. ~~허용 접근 기록의 트랜잭션~~ — **정함 (11/06, #604)**: 같은 트랜잭션 + `MANDATORY`. 거부 기록은 독립 (4절)
3. ~~`StaffCaseScopeReader` 질문지 방식 허용 여부~~ — **정함 (11/07, #643)**: 질문지 방식(fds.api 질문지, investigation 구현). P3 동의
4. ~~직원 정지 상태: `INACTIVE` 재사용 vs `LOCKED` 신설~~ — **정함 (11/07, #647)**: 별도 상태 `SUSPENDED`, 판정으로만 해제 ([ADR-0084](0084-staff-account-security-suspension.md)). P1 확인 필요
5. 결제 준비 요청의 주문자 칸: 선택(실험) vs 필수 — P5 시은 · CakeShop
6. 주문자 원문 보관 기간(실험안: 결제 후 540일 뒤 암호문 파기, 파기 배치는 2차) — P5 시은
7. 관리자 메뉴: 사장님 403 탐지(#515)와 묶어 보일지 — frontend · P2
