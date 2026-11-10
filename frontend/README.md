# DonWorry 프론트엔드

React·TypeScript·Vite·Ant Design 기반의 공개 화면(서비스 소개·이용 신청·결제창)과 역할별 백오피스(관리자·보안 직원·사장님) 화면입니다.
업무 화면은 서버 세션으로 로그인하고, 모든 데이터는 백엔드 업무 API에서 받습니다.

> 이 문서는 `dev` `4a24d40a`(2026-11-09)의 `src/app/router.tsx` · `src/config/navigation.ts` · `src/api/` 와 백엔드 `SecurityConfig` · `application.yml` 을 대조해 썼습니다.
> 구현되어 있다는 뜻이며, 화면 전체를 이 커밋에서 브라우저로 다시 검증했다는 뜻은 아닙니다. 기능별 검증 기록은 각 PR에 있습니다.

## 실행

`frontend/`에서 실행합니다. 로그인은 백엔드(8081)와 DB가 필요하며 `/api` 요청은 Vite 프록시로 전달합니다.

```bash
npm ci
npm run dev
```

터미널에 표시된 주소로 접속합니다. 기본 주소는 `http://localhost:5173`입니다.
`/login`에서 Flyway 시드 계정으로 로그인합니다. ADMIN 시드 비밀번호는 저장소에 없으므로 담당자가 공유한 값을 사용합니다.

| 역할                     | 아이디       | 비밀번호 | 첫 화면          |
| ------------------------ | ------------ | -------- | ---------------- |
| 보안 직원                | `staff_test` | `1234`   | `/security/home` |
| 사장님 (DEMOSHOP 가맹점) | `boss`       | `1234`   | `/owner`         |
| 관리자                   | `admin`      | 담당자   | `/admin/home`    |

보안 직원·사장님은 공개된 시연 계정입니다. 운영 프로필(`prod`)은 시작할 때 이 시연 계정을 정지합니다 ([identity #131](../docs/identity/README.md#131-운영-설정)).
관리 화면은 최소 1280px 기준이며, 작은 창에서는 가로 스크롤을 사용합니다. 공개 화면은 좁은 화면에서 한 열로 표시합니다.

결제부터 매입 완료·AI 보고서까지 이어서 보려면 [로컬 시연 가이드](../docs/demo/local-demo.md)를 따릅니다. HELD Case는 결제 스크립트(`scripts/demo/legacy/cakeshop_pay.py`)로 만듭니다.

## 화면

경로는 `src/app/router.tsx`, 메뉴 구역과 표시 권한은 `src/config/navigation.ts`가 정합니다. HTTP 호출은 `src/api/`에만 둡니다.

### 공개 화면 (로그인 없음)

| 경로                    | 화면                                                    | API 모듈                                                  |
| ----------------------- | ------------------------------------------------------- | --------------------------------------------------------- |
| `/`                     | 서비스 소개. 로그인 상태면 역할·권한별 첫 화면으로 이동 | `auth.ts`                                                 |
| `/apply`                | 가맹점 이용 신청                                        | `merchantApplications.ts` (`publicApi`, 업무 세션과 별개) |
| `/apply/verify`         | 신청 이메일 확인                                        | `merchantApplications.ts`                                 |
| `/apply/password`       | 승인 뒤 첫 비밀번호 설정                                | `merchantApplications.ts`                                 |
| `/login`                | 세션 로그인                                             | `auth.ts`                                                 |
| `/checkout/:sessionKey` | 결제창 (ADR-0064, 결제 상태 2초 조회)                   | `checkout.ts`                                             |

서비스 소개(`/`)는 공개 화면을 처음 볼 때 탭당 한 번 약 2.5초의 브랜드 진입 연출을 보여 줍니다 (#714). 숫자는 실제 다운로드 진행률이 아니며, 기존 화면·API는 뒤에서 동시에 준비됩니다. `바로 시작하기` 또는 Escape로 건너뛸 수 있고, 움직임 줄이기 설정에서는 생략합니다. 완료 여부만 `sessionStorage`에 저장하며 서버 요청·추가 라이브러리는 없습니다.

### 관리자 (`/admin`, ADMIN)

메뉴는 큰 묶음 › 메뉴 2단이고, 같은 대상을 다루는 화면은 메뉴 하나에 본문 위 탭으로 합칩니다 ([ADR-0082](../docs/decisions/0082-insider-access-security.md) 8절). 탭마다 주소는 그대로입니다.

| 메뉴 구역   | 메뉴 › 화면                                                                                                                                                                        | API 모듈                                                             |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| (제목 없음) | 홈 `/admin/home`                                                                                                                                                                   | `adminHome.ts`                                                       |
| 가맹점 관리 | 이용 신청 `applications`, 가맹점 목록·만들기·상세 `merchants`                                                                                                                      | `adminApplications.ts`, `adminMerchants.ts`, `integration.ts`        |
| 직원 관리   | 사건 담당 › [담당 현황 `case-assignments` · 변경 요청 `case-assignment-requests`], 원문 열람 검토 `raw-view-reviews`                                                               | `caseAssignments.ts`, `rawViewReviews.ts`                            |
| 접근 감시   | 내부자 보안 › [관제 `insider-monitor` · 경보 `insider-alerts` · 사건 `insider-cases`], 계정 접근 이상 탐지 `fds/operator-access-detections`                                        | `insiderMonitor.ts`, `insiderAlerts.ts`, `insiderCases.ts`, `fds.ts` |
| 시스템 설정 | 계정 관리·만들기·상세 `users`, 탐지 룰 › [룰 목록 `fds/rules` · 변경 요청 `fds/rule-change-requests` · 변경 이력 `fds/rule-policy-change-histories`], AI 조사 긴급정지 `ai-switch` | `adminAccounts.ts`, `fds.ts`, `aiSwitch.ts`                          |

관리자는 로그인하면 이전 방문 주소 대신 항상 `/admin/home`에서 시작합니다 (#307).
내부자 관제 화면은 화면이 보일 때 2초마다 다시 읽고, 마지막 조작 뒤 5분이 지나면 자동 갱신을 멈춥니다 (#691).

### 보안 직원 (`/security`, SECURITY_STAFF)

| 메뉴 구역 | 화면                                                                                                                         | API 모듈                                                           |
| --------- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 개요      | 홈 `/security/home`                                                                                                          | `cases.ts`                                                         |
| 결제      | 거래 내역 `transactions` (CSV 내보내기 포함, 경보 중에는 차단 — INS-05)                                                      | `paymentTransactions.ts`, `mlShadowScores.ts`                      |
| 탐지      | 경보 `alerts`·상세, 탐지 룰 목록 `rules`, 차단 목록 `blocklist`                                                              | `fds.ts`, `paymentOperations.ts`                                   |
| 조사      | Case 목록 `cases`·상세·AI 보고서 `cases/:id/report`·과거 사건 비교 `cases/:id/compare/:pastId`, AI 조사 긴급정지 `ai-switch` | `cases.ts`, `caseAssignments.ts`, `caseContacts.ts`, `aiSwitch.ts` |
| 조치      | 조치 내역 `actions`                                                                                                          | `cases.ts`                                                         |

Case 판정과 금융 조치 인계는 서버에 저장되고 P5로 요청됩니다. AI 보고서는 서버가 만듭니다(`ai.server.enabled=false`면 고정 문장 보고서).
Case 상세는 보고서 생성 중·금융 처리 대기 중일 때 3초마다 다시 읽고, 마지막 조작 뒤 2분이 지나면 멈춥니다 (#238).

### 사장님 (`/owner`, OPERATOR)

| 메뉴 구역 | 화면                                                                                                      | 접근 조건      |
| --------- | --------------------------------------------------------------------------------------------------------- | -------------- |
| 개요      | 홈 `/owner`                                                                                               | 사장님         |
| 결제 보안 | 조치 결과 `actions`·상세 (`merchantCases.ts`)                                                             | `FDS_VIEW`     |
| 자금관리  | 정산 내역 `settlements`·자금 현황 `funds` (`finance.ts`, `/api/v1/settlements/…`), 연계 대출 상품 `loans` | `FINANCE_VIEW` |
| 연동      | 시크릿 키 발급 `integration` (`integration.ts`)                                                           | 사장님         |

업무 권한이 없어도 홈과 시크릿 키 발급은 이용할 수 있고, 권한 없는 메뉴와 빈 구역은 숨깁니다.
연계 대출 상품은 가상 예시이며 은행 실잔액·실제 신청 API는 연결하지 않습니다. 정산 계약과 수동 확인 목록은 [6단계 연결 문서](../docs/settlement/frontend-connection.md)를 참조합니다.

### 공통 계정 화면

모든 로그인 사용자는 비밀번호 변경 `/account/password`와 내 로그인 이력 `/account/login-history`를 씁니다.

### 개발 서버 전용

`npm run dev`에서만 `/dev/settlements` · `/dev/funds` · `/dev/funds/loans` 정산 미리보기를 엽니다(`import.meta.env.DEV`). 실제 로그인·금액 조회 없이 화면만 확인하며, 배포 빌드에는 없습니다.

## 로그인·세션·인가

- 사용자 정보는 메모리에만 두고 새로고침·경로 이동 시 `/api/auth/me`로 확인합니다. 401은 비로그인/세션 만료, 연결 실패·5xx는 오류·재시도로 구분합니다.
- 30분 무활동이면 만료하고 25분부터 예고·연장 창을 띄웁니다([#166 설계](../docs/identity/166-session-expiry-design.md), ADR-0014). 서버 요청(자동 갱신 포함)은 활동으로 칩니다.
- 미로그인·만료로 `/login`에 가면 원래 경로와 쿼리를 기억했다가, 같은 계정·권한일 때만 복귀합니다 (#165, `auth/loginRedirect.ts`). 강제 비밀번호 변경이 복귀보다 우선합니다.
- `/admin/*` · `/security/*` · `/owner/*` · `/account/*` 는 `auth/AuthRoutes.tsx`의 `ServerSession`이 지킵니다. 다른 역할 화면이나 권한 없는 메뉴는 403과 "내 홈으로"를 보여 줍니다. 권한은 로그인할 때 세션에 담으므로 권한을 바꾸면 다시 로그인해야 반영됩니다.
- **화면 가드는 편의 기능입니다.** 실제 인가는 서버가 합니다. 백엔드는 `SecurityConfig`에 등록하지 않은 경로를 모두 거절합니다(비로그인 401 · 로그인 403, [ADR-0059](../docs/decisions/0059-security-default-deny.md)).
- 상태를 바꾸는 요청(POST · PUT · PATCH · DELETE)은 CSRF 토큰을 헤더에 실어 보냅니다(`api/csrf.ts`, 서버 `security.csrf.enabled` 기본 켜짐, #408). 토큰이 거절되면 새 토큰으로 한 번만 다시 보냅니다.

## 검사

```bash
npm run lint
npm run format:check
npm run test
npm run build
```

CI(`frontend` job)도 같은 순서로 돌립니다 ([CI 04](../docs/ci/04-frontend.md)).
`npm run test`는 `tsc -p tsconfig.test.json` 뒤 Node 기본 TypeScript 실행 기능과 `node:test`로 `tests/*.test.ts`를 돌립니다. 별도 테스트 라이브러리는 쓰지 않습니다.
쇼핑몰 주소의 UTS #46 검증(`tr46@7.0.0`) 때문에 Node 22.14 이상(또는 Node 24 이상)을 사용합니다.

담당 화면을 공통 레이아웃에 연결하는 방법은 [공통 레이아웃 적용 가이드](공통레이아웃_적용가이드.md)를 봅니다.
