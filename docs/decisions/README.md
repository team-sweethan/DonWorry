# 아키텍처 결정 기록 (ADR)

중대한 결정의 **이유 · 버린 대안 · 영향**을 남긴다. Notion은 합의 내용을, 여기는 "왜 그것을 골랐고 무엇을 버렸는지"를 담는다.

## 언제 쓰나

쓴다:

- 모듈 경계 · 의존 방향 변경
- 저장 방식 · 스키마 전략 변경
- 외부 계약(HTTP · 이벤트) 변경
- 기술 · 라이브러리 선택과 교체

안 쓴다:

- 변수명 · 파일 위치 같은 구현 선택
- Notion에 이미 적힌 결정을 그대로 반복하는 것

## 규칙

- 파일명 `NNNN-짧은-제목.md`, 번호는 4자리 연번
- 한 파일에 한 결정
- **채택된 ADR 본문은 고치지 않는다.** 뒤집을 때는 새 ADR을 쓰고 이전 것의 상태만 `대체됨 (→ NNNN)`으로 바꾼다
- 상태가 "제안"인 동안은 PR 리뷰에서 자유롭게 고친다. 팀이 합의하면 "채택"으로 바꾼다
- 템플릿: [`0000-template.md`](0000-template.md)

## 목록

| 번호 | 제목 | 상태 |
| --- | --- | --- |
| [0001](0001-module-dependency-direction.md) | 모듈 간 의존 방향 | 대체됨 (→ 0012) |
| [0002](0002-frontend-common-layout.md) | Ant Design 기반 프론트 공통 레이아웃 | 제안 — 구현 반영, 팀 디자인 승인 필요 |
| [0009](0009-ai-investigation-call-structure.md) | AI 조사 호출 구조 (Spring ↔ FastAPI) | 제안 — 팀 합의 필요 |
| [0010](0010-payment-operation-storage.md) | P5 요청·금융 작업·이력의 최소 저장 분리 | 제안 — 후속 검토, 1차 필수에서 분리 |
| [0011](0011-flyway-migration-versioning.md) | Flyway 전환과 마이그레이션 버전 규칙 | 제안 — 코드·CI 반영, ADR 채택 상태 확인 필요 |
| [0012](0012-payment-settlement-dependency.md) | 모듈 간 의존 방향 — Payment → Settlement 개정 | 채택 |
| [0013](0013-code-formatter.md) | 코드 모양 자동 정리 도구 (palantir-java-format, ruff format) | 제안 — 코드·CI 반영, ADR 채택 상태 확인 필요 |
| [0014](0014-session-authentication.md) | MVP 세션 인증과 단계별 보안 적용 | 채택 |
| [0015](0015-fds-evaluation-list-query-contract.md) | FDS 평가 목록 조회의 서버 페이지네이션과 검색 계약 | 제안 — 팀 합의 필요 |
| [0016](0016-capture-request-event-contract.md) | 1차 MVP 매입 요청 이벤트 계약 | 채택 — P6 요청 계약 |
| [0017](0017-capture-completed-event-contract.md) | 1차 MVP 매입 완료 이벤트 계약 | 채택 — P5 수신 구현 별도 |
| [0018](0018-payment-mvp-capture-completion.md) | 1차 CAPTURE 완료 통지로 영속 접수 확인 | 대체됨 (→ 0027) |
| [0019](0019-security-case-http-contract.md) | P3 Case 조회 · 판정 HTTP 계약 | 제안 — 팀 합의 필요 |
| [0020](0020-identity-public-read-contracts.md) | Identity 공개 조회 계약과 가맹점 범위 | 제안 — #124 구현 반영, PR 리뷰 대상 |
| [0021](0021-single-session-and-logout-all.md) | 계정당 단일 세션과 모든 기기 로그아웃 | 제안 — #125 구현 반영, PR 리뷰 대상 |
| [0022](0022-self-service-password-change.md) | 본인 비밀번호 변경과 로그인 직렬화 | 제안 — #128 구현 반영, PR 리뷰 대상 |
| [0023](0023-payment-held-event-contract.md) | P5 HELD 이벤트 계약 | 채택 — P3 필드·필수 조건·시각 기준 확인 |
| [0024](0024-admin-account-management.md) | 관리자 계정 관리와 강제 비밀번호 변경 | 제안 — #129 사용자 구현 승인, PR 리뷰 대상 |
| [0025](0025-csrf-preparation.md) | CSRF 토큰 교환 준비와 검사 설정 분리 | 제안 — #126 사용자 구현 승인, PR 리뷰 대상 |
| [0026](0026-payment-read-api.md) | P5 결제 정보 단건 공개 조회 계약 | 제안 — #139 사용자 확정안·구현 승인, PR 리뷰 대상 |
| [0027](0027-payment-actions.md) | P5 직원 금융 조치 단일 저장과 완료 기반 인계 | 제안 — #158 P3 계약 확인 반영, CAPTURE 확정 실패는 P5·P6 후속 협의 |
| [0028](0028-admin-merchant-management.md) | 관리자 가맹점 관리와 정지·로그인 순서 보장 | 제안 — #130 사용자 구현 승인, PR 리뷰 대상 |
| [0029](0029-login-attempt-lock.md) | 로그인 연속 실패의 고정 시간 잠금 | 제안 — #127 사용자 구현 승인, PR 리뷰 대상 |
| [0030](0030-cakeshop-payment-attempts.md) | CakeShop 주문별 결제 시도와 HTTP 조회 | 제안 — #149 저장 구조 방향 사용자 승인, 상세 설계·인증 연결 확인 중 |
| [0031](0031-fds-evaluation-request-id-conflict.md) | FDS 평가 요청 ID 재사용 충돌 처리 | 제안 — 팀 합의 필요 |
| [0032](0032-production-admin-startup.md) | 운영 설정 분리와 관리자 비밀번호 기동 적용 | 제안 — #131 부분 구현, 시연 계정 정지는 후속 |
| [0033](0033-login-history.md) | 로그인 이력의 독립 저장과 90일 조회 | 제안 — #147 사용자 승인·구현 반영, 팀 리뷰 대상 |
| [0034](0034-fds-evidence-read-contract.md) | P3 Case 화면용 FDS 평가 근거 공개 조회 계약 | 제안 — #162 구현·P3 연동 대기 |
| [0035](0035-long-held-payment-policy.md) | 장기 보류 결제의 확인 기한과 금융 종료 분리 | 전체 정책 제안 — 내부 조회 담당 확인 완료, 운영 시간·담당·CakeShop 정책·구현 미완료 |
| [0036](0036-fds-failure-analysis-and-reevaluation.md) | P2 FDS 오류 분석·재탐지 계약 | 제안 — P2 구현, P5 연동 대기 |
| [0037](0037-merchant-hmac-authentication.md) | CakeShop 요청 HMAC 인증과 가맹점 신원 | 제안 — #219 사용자 구현·스키마 승인, P6 후속·PR 리뷰 대상 |
| [0038](0038-settlement-identity-dependency.md) | Settlement → Identity 의존 허용 (ADR-0012 보류 항목 확정) | 제안 — #240·#241 선행, 팀 합의 필요 |

## 현재 적용 상태와 기록의 구분

2026-10-22, `ba9427f` 기준으로 ADR-0011의 Flyway·버전 생성·DB 검사와 ADR-0013의 포매터·CI 검사가 구현되어 있습니다.
AGENTS에 적힌 적용 규칙은 따르되, 구현·병합 사실만으로 ADR 상태를 `채택`으로 바꾸지 않습니다.

ADR-0012의 허용 의존 목록은 #60에서 구현됐습니다. 채택된 ADR 본문의 도입 당시 검증 계획은 보존하며, 현재 검사 범위는 [Modulith 05](../modulith/05-verification.md)를 봅니다.
ADR-0001은 이전 결정의 기록입니다. 현재 구현 안내에서 의존 방향을 설명할 때는 ADR-0012를 참조합니다.
Notion에 남은 이전 설명과 미결 계약은 [문서 대조 현황](../README.md)에 기록합니다.

0003~0008은 미채택 P5 초안으로, 1차 시연 문서 재작성 시 삭제했다. 이전 내용은 Git 이력(`2d84406`)에서 확인할 수 있으며 번호를 재사용하지 않는다.
