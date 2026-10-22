# 04. frontend 검사

[← CI 문서 목차](README.md)

워크플로: [`.github/workflows/frontend.yml`](../../.github/workflows/frontend.yml) · job 이름 `frontend`

## 1. 단계 한눈에

```text
변경 감지 ──(frontend/ 변경 없음)──▶ 통과
   ▼
   Node 22 준비 (npm 캐시 사용)
   ▼
① npm ci                ← package-lock.json 그대로 설치
   ▼
② npm run lint          ← oxlint: 코드 규칙 검사
   ▼
③ npm run format:check  ← Prettier: 코드 모양 검사 (고치지 않고 확인만)
   ▼
④ npm run build         ← tsc -b (타입 검사) + vite build (번들링)
```

| 단계 | 하는 일 | 실패하면 의심할 것 | 로컬에서 똑같이 (`frontend/`에서) |
| --- | --- | --- | --- |
| ① `npm ci` | 잠금 파일 기준 설치 | `package.json`만 고치고 `package-lock.json`을 커밋 안 함 | `npm ci` |
| ② lint | oxlint 규칙 검사 | `any` 사용, hooks 규칙 위반 | `npm run lint` |
| ③ format:check | Prettier 포맷 확인 | 저장 시 포맷을 안 맞춤 | `npm run format:check` → 고치기: `npm run format` |
| ④ build | 타입 검사 + 빌드 | 타입 오류, 없는 파일 import | `npm run build` |

## 2. `npm ci`와 `npm install`의 차이

| | `npm install` | `npm ci` |
| --- | --- | --- |
| 기준 | `package.json` (범위 안에서 최신 버전 가능) | `package-lock.json` (적힌 버전 그대로) |
| 잠금 파일과 어긋나면 | 잠금 파일을 고쳐 버림 | **실패** |
| 용도 | 라이브러리 추가·변경할 때 | CI, 깨끗하게 다시 설치할 때 |

CI는 모든 PC에서 같은 버전이 깔리도록 `npm ci`를 씁니다. 라이브러리를 추가했다면 `npm install`로 바뀐 **`package-lock.json`도 함께 커밋**합니다.

## 3. oxlint 규칙

설정 파일: `frontend/.oxlintrc.json` (플러그인 `react` · `typescript` · `oxc`)

| 규칙 | 수준 | 뜻 |
| --- | --- | --- |
| `typescript/no-explicit-any` | error | `any` 타입 금지. 타입을 모르면 `unknown`을 쓰고 좁혀서 사용 ([AGENTS.md 5절](../../AGENTS.md#5-frontend-규칙)) |
| `react/rules-of-hooks` | error | `useState` 등 hook을 조건문·반복문 안에서 부르지 않기 |
| `react/only-export-components` | warn | 컴포넌트 파일에서는 컴포넌트만 export (개발 중 화면 즉시 갱신이 깨지지 않게). 경고라 CI는 통과 |

## 4. Prettier

설정 파일: `frontend/.prettierrc.json` — 세미콜론 없음, 작은따옴표, 한 줄 100자

- CI는 `--check`로 **확인만** 합니다. 고치지 않습니다
- 실패하면 로컬에서 `npm run format`(= `prettier --write .`)을 한 번 돌리고 커밋하면 됩니다
- 편집기에 Prettier 확장을 깔고 "저장 시 포맷"을 켜 두면 실패할 일이 거의 없습니다

## 5. 빌드

`npm run build` = `tsc -b && vite build`

- `tsc -b`: TypeScript 타입 검사. 타입이 맞지 않으면 여기서 실패하고 빌드로 넘어가지 않습니다
- `vite build`: 배포용 파일(`dist/`)을 만듭니다. 대소문자가 다른 import 경로처럼 Windows에서는 되지만 리눅스(CI)에서 실패하는 문제도 여기서 잡힙니다

다음: [05 ai-server · docs 검사](05-ai-server-and-docs.md)
