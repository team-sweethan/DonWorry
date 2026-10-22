# DonWorry 프론트엔드

React·TypeScript·Vite·Ant Design 기반 백오피스 화면입니다.
로그인·헤더·사이드바, Case·경보 목업과 담당자 구현 영역을 제공하며, 실제 인증·업무 API는 연결 전입니다.

## 실행

`frontend/`에서 실행합니다. 백엔드나 별도 환경 파일 없이 시연할 수 있습니다.

```bash
npm ci
npm run dev
```

터미널에 표시된 주소로 접속합니다. 기본 주소는 `http://localhost:5173`입니다.

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
| `/login`                                   | 로컬 시연 로그인                                                                     |
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
```
