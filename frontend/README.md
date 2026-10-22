# DonWorry 프론트엔드

React·TypeScript·Vite·Ant Design 기반 백오피스 화면입니다.
서버 세션 기반 ADMIN 로그인·관리자 메뉴와 Case·경보 시연 화면을 제공합니다. 업무 API는 연결 전입니다.

## 실행

`frontend/`에서 실행합니다. 실제 로그인은 백엔드(8081)와 DB가 필요하며 `/api` 요청은 Vite 프록시로 전달합니다. 기존 시연 화면은 백엔드 없이 사용할 수 있습니다.

```bash
npm ci
npm run dev
```

터미널에 표시된 주소로 접속합니다. 기본 주소는 `http://localhost:5173`입니다.
시연 화면은 `/demo/login`에서 아래 계정으로 로그인합니다. `/login`은 실제 로그인 화면입니다. ADMIN 시드 비밀번호는 저장소에 없으므로 담당자가 공유한 값을 사용합니다.

| 시연 역할 | 아이디     | 비밀번호   |
| --------- | ---------- | ---------- |
| 보안 직원 | `security` | `demo1234` |
| 사장님    | `owner`    | `demo1234` |

공개된 로컬 시연 계정이며 실제 인증·인가를 제공하지 않습니다.
관리 화면은 최소 1280px 기준이며, 작은 창에서는 가로 스크롤을 사용합니다.

## 개발

현재 라우터에 연결된 화면은 다음과 같습니다.

| 경로                                       | 현재 상태                                                                            |
| ------------------------------------------ | ------------------------------------------------------------------------------------ |
| `/login`                                   | 실제 세션 로그인·입력 검증·오류 안내 (#78)                                           |
| `/`, `/admin`                              | `/admin/fds`로 이동 후 세션·ADMIN 역할 확인                                          |
| `/admin/fds`, `/admin/finance`             | ADMIN 전용 메뉴·헤더와 담당자 구현 영역 (`EmptyPage`)                                |
| `/demo/login`                              | 로컬 시연 로그인                                                                     |
| `/security/cases`, `/security/cases/:id`   | Case 목록·상세 목업. `src/api/cases.ts`의 메모리 저장소로 조회·판정·인계 진행을 시연 |
| `/security/alerts`, `/security/alerts/:id` | 경보 목록·상세 목업. `src/demo/alertMockData.ts` 사용                                |
| `/security/transactions`                   | 거래 내역 담당자 구현 영역 (`EmptyPage`)                                             |
| `/owner/actions`, `/owner/settlements`     | 조치 결과·정산 현황 담당자 구현 영역 (`EmptyPage`)                                   |
| `/dev/components`                          | 공통 UI 예시                                                                         |

목업의 판정·보고서·인계 상태 변경은 서버 저장이나 실제 금융 실행을 의미하지 않습니다.

[공통 레이아웃 적용 가이드](공통레이아웃_적용가이드.md)에서 담당 페이지 연결 방법과 수정 위치를 확인합니다.
공통 UI 예시는 사이드바의 **UI 컴포넌트 보기**에서 확인할 수 있습니다.

```bash
npm run lint
npm run format:check
npm run build
npm run test
```

`npm run test`는 Node 22.6 이상에서 기본 TypeScript 실행 기능과 `node:test`를 사용합니다. 별도 테스트 라이브러리는 추가하지 않습니다.

실제 사용자 정보는 메모리에만 두고 새로고침·관리자 경로 이동 시 `/api/auth/me`로 확인합니다. 확인 중에는 보호 본문을 표시하지 않으며, 401은 비로그인/세션 만료로 처리하고 연결 실패·5xx는 관리자 화면에서 오류·재시도로 구분합니다. 로그인 화면은 보호 본문이 없으므로 경고와 함께 로그인 폼을 그대로 보여줍니다. 로그아웃은 서버 204 응답 후 완료합니다.

화면 가드는 편의 기능입니다. 서버는 `/api/auth/me`만 인증을 요구하고 다른 API 접근 제한·CSRF 활성화는 #68 5단계입니다. `/me`는 세션 정보를 반환하며 계정 상태 변경을 DB에서 다시 조회하지 않습니다.

실제 ADMIN 메뉴는 `adminNavigation`과 `auth/`에서 관리합니다. 시연용 `Role`, `DemoContext`, `/security/*`, `/owner/*`는 기존 방식으로 유지합니다.
