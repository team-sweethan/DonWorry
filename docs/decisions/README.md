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

P1 #521: [0079 사장님 403 이벤트 타입과 계정 상태 조회 계약](0079-operator-security-contracts.md) — 제안, #515 경계 유지. 실제 발행·P2 연결은 후속.

P1 #517: [0078 시스템 수행자 감사 기록과 사장님 계정 정지](0078-operator-account-suspension.md) — 제안, 사용자 설계·구현 승인. #515 전체 경계 합의와 구분한다.

P6 추가 제안: [0053 정기 지출 규칙과 회차별 완료 기록](0053-p6-recurring-expense-occurrences.md).

| 번호 | 제목 | 상태 |
| --- | --- | --- |
| [0001](0001-module-dependency-direction.md) | 모듈 간 의존 방향 | 대체됨 (→ 0012) |
| [0002](0002-frontend-common-layout.md) | Ant Design 기반 프론트 공통 레이아웃 | 제안 — 구현 반영, 팀 디자인 승인 필요 |
| [0009](0009-ai-investigation-call-structure.md) | AI 조사 호출 구조 (Spring ↔ FastAPI) | 제안 — 팀 합의 필요 |
| [0010](0010-payment-operation-storage.md) | P5 요청·금융 작업·이력의 최소 저장 분리 | 제안 — 후속 검토, 1차 필수에서 분리 |
| [0011](0011-flyway-migration-versioning.md) | Flyway 전환과 마이그레이션 버전 규칙 | 제안 — 코드·CI 반영, ADR 채택 상태 확인 필요 |
| [0012](0012-payment-settlement-dependency.md) | 모듈 간 의존 방향 — Payment → Settlement 개정 | 의존 규칙 채택 유지, 취소 제한 전제만 0054로 대체 |
| [0013](0013-code-formatter.md) | 코드 모양 자동 정리 도구 (palantir-java-format, ruff format) | 제안 — 코드·CI 반영, ADR 채택 상태 확인 필요 |
| [0014](0014-session-authentication.md) | MVP 세션 인증과 단계별 보안 적용 | 세션 인증 채택 유지, 그 외 경로 permitAll 항목만 0059로 대체 |
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
| [0025](0025-csrf-preparation.md) | CSRF 토큰 교환 준비와 검사 설정 분리 | 기본값·활성화 연기만 0063으로 대체, 토큰 계약 유지 |
| [0026](0026-payment-read-api.md) | P5 결제 정보 단건 공개 조회 계약 | 제안 — #139 사용자 확정안·구현 승인, PR 리뷰 대상 |
| [0027](0027-payment-actions.md) | P5 직원 금융 조치 단일 저장과 완료 기반 인계 | 제안 — #158 P3 계약 확인 반영, CAPTURE 확정 실패는 P5·P6 후속 협의 |
| [0028](0028-admin-merchant-management.md) | 관리자 가맹점 관리와 정지·로그인 순서 보장 | 제안 — #130 사용자 구현 승인, PR 리뷰 대상 |
| [0029](0029-login-attempt-lock.md) | 로그인 연속 실패의 고정 시간 잠금 | 제안 — #127 사용자 구현 승인, PR 리뷰 대상 |
| [0030](0030-cakeshop-payment-attempts.md) | CakeShop 주문별 결제 시도와 HTTP 조회 | 제안 — #149 저장 구조 방향 사용자 승인, 상세 설계·인증 연결 확인 중 |
| [0031](0031-fds-evaluation-request-id-conflict.md) | FDS 평가 요청 ID 재사용 충돌 처리 | 제안 — 팀 합의 필요 |
| [0032](0032-production-admin-startup.md) | 운영 설정 분리와 관리자 비밀번호 기동 적용 | 제안 — #131 PR B 운영 시작 시 시연 계정 정지 확장 |
| [0033](0033-login-history.md) | 로그인 이력의 독립 저장과 90일 조회 | 제안 — #147 사용자 승인·구현 반영, 팀 리뷰 대상 |
| [0034](0034-fds-evidence-read-contract.md) | P3 Case 화면용 FDS 평가 근거 공개 조회 계약 | 제안 — #162 구현·P3 연동 대기 |
| [0035](0035-long-held-payment-policy.md) | 장기 보류 결제의 확인 기한과 금융 종료 분리 | 전체 정책 제안 — 내부 조회 담당 확인 완료, 운영 시간·담당·CakeShop 정책·구현 미완료 |
| [0036](0036-fds-failure-analysis-and-reevaluation.md) | P2 FDS 오류 분석·재탐지 계약 | 전체 계약 제안 — 내부 ERROR 정책·명시적 실행 방식 합의 완료, P5 저장·API 및 잠금·전달 복구·CSRF 설계 사용자 승인·문서화, 구현·통합 검증 전 |
| [0037](0037-merchant-hmac-authentication.md) | CakeShop 요청 HMAC 인증과 가맹점 신원 | 제안 — #219·#241 사용자 구현 승인, P6 업무 연결 후속·PR 리뷰 대상 |
| [0038](0038-settlement-identity-dependency.md) | Settlement → Identity 의존 허용 (ADR-0012 보류 항목 확정) | 채택 — #240·#241 선행, P1 결정 (P6 별도 리뷰 없음) |
| [0039](0039-owner-finance-session-authorization.md) | 사장님 정산·자금관리의 세션 인가 | 채택 — P1 결정 (P6 확인 전), P6 실제 통합 후속. 모든 메서드 통과 부분은 0067로 대체 |
| [0040](0040-fds-v02-rule-input-contract.md) | FDS v0.2 룰 입력과 P2 정책 저장소 | 제안 — P2·P5 구현 중 |
| [0041](0041-ai-server-internal-api-key-authentication.md) | AI 서버 내부 API 서비스 키 인증 | 제안 — #266 P1 동의, PR 리뷰 대상 |
| [0042](0042-fds-rule-management.md) | FDS 룰 활성화와 변경 승인 관리 | 제안 — #283 구현 반영, PR 리뷰 대상 |

| [0043](0043-application-storage.md) | 이용 신청·토큰·개인정보 저장 | 제안 — P1 설계·구현 승인, PR 리뷰 대상 |
| [0044](0044-application-smtp.md) | 신청 메일 SMTP와 커밋 후 발송 | 제안 — P1 설계·구현 승인, PR 리뷰 대상 |
| [0045](0045-application-public-http.md) | 공개 신청·이메일 확인 HTTP와 CSRF | 제안 — P1 설계·구현 승인, PR 리뷰 대상 |
| [0046](0046-merchant-directory-and-fds-filter.md) | 가맹점 목록 공개 조회와 FDS 경보 가맹점 필터 | 제안 — P1·P2 구현 반영, PR 리뷰 대상 |

| [0046](0046-application-approval-and-initial-password.md) | 이용 신청 승인 원자성과 첫 비밀번호 설정 | 제안 — P1 설계·구현 승인, PR 리뷰 대상 |
| [0047](0047-application-recovery-and-retention.md) | 신청 메일 수동 복구와 보관 기한 정리 | 제안 — #315 PR 4 사용자 구현 승인·구현 반영, PR 리뷰 대상 |

| [0048](0048-tenant-pg-fee-read-contract.md) | 가맹점별 PG 수수료율 공개 조회 | 제안 — #356 P6 요청·P1 사용자 구현 승인, PR 리뷰 대상 |
| [0051](0051-payment-transactions-read.md) | 보안 직원 거래 내역 조회 — 모든 결제 · 서버 가리기 | 제안 — #363 P5 · P1 확인, PR 리뷰 대상 |

| [0049](0049-application-shop-url.md) | 신청 검토용 쇼핑몰 주소 수집 | 제안 — #354 PR A 사용자 설계·구현 승인, PR 리뷰 대상 |

| [0052](0052-merchant-key-management.md) | 가맹점 HMAC 키 암호화 저장·사장님 관리 | 제안 — #354 PR B 사용자 승인, PR 리뷰 대상 |

| [0054](0054-full-cancellation-policy.md) | 전액 취소·환불과 정산 완료 후 취소 허용 | 사용자 정책 확인, 상세 연동 계약 후속 합의 |
| [0059](0059-security-default-deny.md) | 직원 보안 체인의 기본 거절 (`anyRequest().denyAll()`) | 기본 거절 유지, Case 임시 공개만 0065로 대체 |

| [0060](0060-identity-status-names.md) | 계정·신청 상태 이름과 데이터 보존 전환 | 제안 — #131 PR A 사용자 구현 승인, PR 리뷰 대상 |
| [0061](0061-fds-ml-shadow-inference.md) | FDS ML Shadow 평가 — RF 추론을 ai-server에 두고 결제 판정과 분리 | 제안 — #375 PR ① 구현, PR 리뷰 대상 |
| [0062](0062-fds-ml-shadow-spring-connection.md) | FDS ML Shadow Spring 연결 — P4가 커밋된 평가를 주기적으로 읽어 채점 · 기록 | 제안 — #409 P2 조회 구현, PR 리뷰 대상 |
| [0063](0063-csrf-default-enabled.md) | 브라우저 변경 요청의 CSRF 검사 기본 활성화 | 제안 — #408 PR B 사용자 구현 승인, PR 리뷰 대상 |
| [0064](0064-hosted-checkout-and-payment-webhook.md) | 돈워리 호스팅 결제창과 결제 상태 웹훅 | 채택 — 구현 완료(#425 · #449 · #451 · #455), P1 확인 완료. 4절 · 4-1 개정(#492): `HELD`는 가맹점에 "결제 확인 중", 진행은 가맹점 선택, 사후 거절 손실은 가맹점 부담 |

| [0065](0065-case-session-authorization.md) | Case HTTP 경로의 세션 역할 인가 | 제안 — #408 PR C 사용자 구현 승인, PR 리뷰 대상 |
| [0066](0066-fds-error-analysis-detail-contract.md) | FDS 경보 상세의 오류 원인·분석 결과 공개 계약 | 제안 — 사용자 구현 승인, PR 리뷰 대상 |
| [0067](0067-role-api-matrix.md) | 직원 세션 API의 역할 × 권한표와 가맹점 범위 | 제안 — #420 P1 결정, PR 리뷰 대상 |

## 현재 적용 상태와 기록의 구분

- [0058 CAPTURED 전액 환불과 P6 성공 전달](0058-captured-refund-contract.md): 제안 — P6 성공 전달 방향 확인·P5 모의 환불 기반 구현. 실제 수신·정산 경합·운영 복구 후속 (#383).

- [0057 HELD 고객 모의 승인 취소와 P3 성공 전달](0057-held-customer-void-contract.md): 제안 — P5 실행 기반 구현, P3 계약/Case 정책·운영 활성화 후속 (#382).

- [0056 고객 취소 요청 식별과 결과 조회 계약](0056-cancellation-request-contract.md): HTTP 계약 제안, 재요청 키·정지 가맹점 정책 사용자 확인 (#381).

- [0055 진행 중 결제 취소의 일시 거절과 조회 후 재요청](0055-inflight-cancellation-policy.md): 사용자 정책 확인, 상세 계약·구현 후속 (#380).

P6 PR 리뷰 대상: [0043 주문·픽업 수신과 상품 스냅샷 저장](0043-p6-pickup-receiving.md) — #226·#227·#333.

2026-10-22, `ba9427f` 기준으로 ADR-0011의 Flyway·버전 생성·DB 검사와 ADR-0013의 포매터·CI 검사가 구현되어 있습니다.
AGENTS에 적힌 적용 규칙은 따르되, 구현·병합 사실만으로 ADR 상태를 `채택`으로 바꾸지 않습니다.

ADR-0012의 허용 의존 목록은 #60에서 구현됐습니다. 채택된 ADR 본문의 도입 당시 검증 계획은 보존하며, 현재 검사 범위는 [Modulith 05](../modulith/05-verification.md)를 봅니다.
ADR-0001은 이전 결정의 기록입니다. 현재 구현 안내에서 의존 방향을 설명할 때는 ADR-0012를 참조합니다.
Notion에 남은 이전 설명과 미결 계약은 [문서 대조 현황](../README.md)에 기록합니다.

0003~0008은 미채택 P5 초안으로, 1차 시연 문서 재작성 시 삭제했다. 이전 내용은 Git 이력(`2d84406`)에서 확인할 수 있으며 번호를 재사용하지 않는다.

P6 5단계 PR 리뷰 대상: [0062 실제 기록과 배치 기준의 현금흐름 예측](0062-p6-cash-flow-forecast.md) — #230·#231.

P6 #465 PR 리뷰 대상: [0068 매입일 기준 영업일 D+3 정산](0068-p6-capture-business-day-settlement.md) — 픽업 상세 정보와 정산일 계산 분리.

P1 #461 PR 리뷰 대상: [0069 릴리스 marker 사전 준비와 게시 직전 main 확인](0069-release-marker-publication.md) — 제안, 실제 배포 차단은 #448 후속.

P6 #474 PR 리뷰 대상: [0070 가맹점별 일별 지급 원장](0070-p6-merchant-daily-payouts.md).

P6 #478 PR 리뷰 대상: [0071 환불 성공 수신과 정산 적용 분리](0071-p6-refund-receiving-ledger.md).

P6 #488 PR 리뷰 대상: [0072 지급 전 환불 제외와 가맹점 공통 잠금](0072-p6-unpaid-refund-exclusion.md).

P6 #493 PR 리뷰 대상: [0073 환불 회수 배분과 개별 잔여액](0073-p6-refund-recovery-allocations.md).

P1 #480 PR 리뷰 대상: [0073 Marker digest로 식별하는 릴리스 이미지 묶음](0073-release-bundle-metadata.md) — 제안, Jenkins·VM 소비는 #481 후속.

P1 #481 PR 리뷰 대상: [0074 Jenkins가 승인한 릴리스 묶음으로 VM 업데이트](0074-approved-release-update.md) — 제안, 운영 설치·실검증 전.

P1 #482 PR 리뷰 대상: [0075 VM 공통 잠금과 지속 실행 기록으로 배포 중단 확인](0075-deployment-serialization-and-interruption.md) — 제안, 운영 설치·실검증 전.

P6 #507 PR 리뷰 대상: [0076 실제 지급 원장을 잔액 유입의 기준으로 사용](0076-p6-payout-based-cash-flow.md).
