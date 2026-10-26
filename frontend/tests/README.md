# 인증 검증

`npm run test`는 `tsconfig.test.json`으로 테스트 타입을 검사한 뒤 Node 기본 테스트 러너로 API 오류 처리와 인증 상태 전이를 검사한다. Node 22.6 이상이 필요하며 별도 테스트 라이브러리는 쓰지 않는다.

- `authStore.test.ts`: HTTP 경계(`login` · `getMe` · `logout`)만 대체하고 실제 상태 관리 코드를 실행한다
- `client.test.ts`: `fetch` 응답만 대체한다
- `crossTabSession.test.ts`: 독립 API 모듈 두 개의 탭별 메모리와 공유 저장소·쿠키를 연결해 다른 탭의 인증 변경 중 요청 취소를 검사한다. 실제 브라우저 통합 검증과 구분한다
- `loginRedirect.test.ts`: 실제 목적지 결정 함수에 잘못된 Router state·외부/우회 경로·역할·권한·이전 계정·강제 비밀번호 변경 조건을 입력해 복귀와 대체 경로를 검사한다. React 라우팅과 실제 브라우저 세션 검증은 별도로 수행한다

## 작성 시 주의

- 테스트가 불러오는 `src/` 파일(`api/client.ts` · `api/auth.ts` · `auth/authStore.ts` · `types/auth.ts`)과 그 파일이 다시 불러오는 파일은 import에 `.ts` 확장자를 붙인다. Node의 타입 제거 실행은 확장자 없는 경로를 찾지 못한다
- 같은 이유로 이 파일들에는 React · antd · `.tsx`를 import하지 않는다. 화면 동작은 브라우저에서 확인한다
- `enum` · 매개변수 속성처럼 타입 제거만으로 실행할 수 없는 문법은 쓰지 않는다 (`erasableSyntaxOnly`)

실행 결과와 미검증 항목은 이 파일이 아니라 PR 본문의 "테스트 결과" · "미검증"에 기록한다.
