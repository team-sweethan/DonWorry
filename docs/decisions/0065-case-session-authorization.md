# 0065. Case HTTP 경로의 세션 역할 인가

- **상태:** 제안 — #408 PR C 사용자 구현 승인, PR 리뷰 대상
- **날짜:** 2026-11-02
- **관련:** Issue #408, #68, [ADR-0059](0059-security-default-deny.md) (Case 경로의 임시 `permitAll` 항목을 대체), [ADR-0019](0019-security-case-http-contract.md) (HTTP 인증 오류 코드 `INV_010` · `INV_009` · `INV_014`를 대체)
- **영향 받는 담당:** P1 주환 (보안 필터), P3 수민 (Case·가맹점 조회·판정), P4 현규 (보고서 요청·진행 조회)

## 배경

ADR-0059는 Case 경로 11개를 정확한 메서드·경로로 열고 컨트롤러·서비스의 Guard에 역할 검사를 맡겼다. 이슈 #408에서 기존 Guard와 같은 정책을 보안 필터에도 등록하고, Spring Security 인증 없이 `CurrentStaff` 대역만 사용하던 HTTP 테스트를 함께 수정하기로 했다.

## 결정

`SecurityConfig`에 아래 메서드·경로만 등록하고 기존 Guard는 유지한다. 새 역할·가맹점 정책은 만들지 않는다.

| 역할 조건 | 메서드·경로 |
| --- | --- |
| `SECURITY_STAFF`만 (`ADMIN` 제외) | `GET /api/security-cases`, `/counts`, `/payment-links`, `/handoffs`, `/{caseId}` |
| `SECURITY_STAFF`만 (`ADMIN` 제외) | `POST /api/security-cases/{caseId}/decisions`, `/{caseId}/handoffs/{handoffId}/recheck`, `/{caseId}/reports` |
| `SECURITY_STAFF`만 (`ADMIN` 제외) | `GET /api/security-cases/{caseId}/reports/{reportId}/progress` |
| `OPERATOR` + 세션 `merchantId` 있음 + `FDS_VIEW` | `GET /api/merchant/security-cases`, `/{caseId}` |

보안 필터에서 거절하면 비로그인은 401 `AUTH_001`, 역할·가맹점·권한 조건 위반은 403 `AUTH_002`다. 기존 HTTP 응답의 `INV_010`·`INV_009`·`INV_014`를 대체한다. Guard 자체의 오류 코드는 유지한다. 필터 통과 뒤 Guard에서 로그인 사용자를 확인할 수 없는 경우 등에는 기존 `INV_*` 응답이 나올 수 있다.

CSRF 계약은 [ADR-0063](0063-csrf-default-enabled.md)을 유지한다. 변경 요청에 토큰이 없거나 잘못되면 역할 검사에 앞서 403 `CSRF_001`이다. 위 401·403 인증 검증은 유효한 CSRF 토큰이 있는 조건이다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| Case의 `permitAll`과 Guard 검사만 유지 | 직원 보안 체인의 역할 정책에서 Case만 빠지고 인증 오류도 업무 모듈마다 달라진다 |
| `authenticated()`만 적용 | ADMIN·OPERATOR도 필터를 통과해 기존 역할 정책을 필터에서 보장하지 못한다 |
| 접두사 `/**`로 허용 | 같은 접두사에 새 API를 추가할 때 등록 누락을 발견하지 못한다 |
| Guard 제거 | 서비스 직접 호출과 판정 주체·가맹점 확인의 기존 보호를 잃는다 |
| 필터에서 기존 `INV_*` 오류 반환 | identity 보안 필터가 investigation 업무 오류 계약에 의존하게 된다. 공통 `AUTH_*` 응답을 사용한다 |

## 영향

- API: 위 11개 경로의 필터 인가와 인증 오류 코드가 바뀐다. 다른 가맹점 Case의 404와 업무 검증 오류는 유지한다. 등록하지 않은 메서드·경로는 기본 거절한다.
- DB: 없음. 모듈 간 공개 API·이벤트·의존 방향 변경도 없다.
- 화면: 공통 클라이언트의 HTTP 상태 기반 401·403 처리는 유지한다. `cases.ts`·`merchantCases.ts`의 오류 코드 주석을 갱신한다.
- 테스트: 공통 `SessionUserTestSupport`로 `CurrentStaff` 대역과 HTTP 인증 principal을 맞춘다. `CaseFlowTests`·`MerchantCaseFlowTests`는 실제 업무 흐름과 공통 오류 응답을 검증한다. `AiInvestigationFlowTests`의 진행 조회에 보안 직원 인증을 붙인다. `AuthHttpTests`는 실제 쿠키 로그인 전 401·로그인 후 잘못된 Case ID의 400 보존을 확인한다. `HandoffReceiptTests`는 서비스 직접 호출뿐이라 수정하지 않는다.
- 문서: ADR-0059의 Case 임시 공개 결정을 이 ADR로 대체하고 investigation README에 현재 계약을 연결한다. ADR-0019의 인증 오류 코드 설명에 필터 응답(`AUTH_001` · `AUTH_002`)을 덧붙인다.

## 검증

- 실행 결과는 PR의 테스트 결과에 검토 대상 커밋과 함께 기록한다.
- 미검증: 실제 브라우저의 역할별 Case 화면 수동 확인, 운영 배포 환경.
