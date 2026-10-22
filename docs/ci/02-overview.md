# 02. 언제 무엇이 도나

[← CI 문서 목차](README.md)

## 1. 워크플로 4개 한눈에

| 워크플로 파일 | job 이름 | 검사 대상 폴더 | 하는 일 (순서대로) | 상세 |
| --- | --- | --- | --- | --- |
| `backend.yml` | `backend` | `backend/` | (PR) migration 검사 → `./gradlew check` → `./gradlew mariaDbTest` | [03](03-backend.md) |
| `frontend.yml` | `frontend` | `frontend/` | `npm ci` → oxlint → Prettier 검사 → 타입 검사 + 빌드 | [04](04-frontend.md) |
| `ai-server.yml` | `ai-server` | `ai-server/` | `uv sync --locked` → ruff → pytest | [05](05-ai-server-and-docs.md#1-ai-server) |
| `docs.yml` | `docs` | 모든 `*.md` | Markdown 상대 링크 검사 | [05](05-ai-server-and-docs.md#2-docs) |

## 2. 실행 조건

| 상황 | backend · frontend · ai-server | docs |
| --- | --- | --- |
| **PR** 생성·push | **항상 시작**. 자기 폴더가 안 바뀌었으면 나머지 단계를 건너뛰고 약 10초 만에 통과 | `.md` 파일이 바뀐 PR에서만 |
| `dev` · `main`에 **push** (= PR 병합) | 자기 폴더가 바뀌었을 때만 (경로 필터) | `.md` 파일이 바뀌었을 때만 |

자기 워크플로 파일(예: `backend.yml`)을 고쳐도 그 폴더가 바뀐 것으로 칩니다.

### 왜 PR에서는 "항상 시작하고 건너뛰나"

GitHub 설정에서 `backend` · `frontend` · `ai-server`는 **필수 검사**입니다. 필수 검사는 "통과" 결과가 와야 병합 버튼이 열립니다.

```text
경로 필터로 아예 안 돌리면 (예: README만 고친 PR)
  backend 검사가 시작조차 안 함 → 결과가 영영 안 옴
  → PR 화면: "Expected — Waiting for status to be reported" → 병합 불가 ❌

지금 방식
  backend 검사 시작 → 첫 단계에서 "backend/ 변경 없음 — 검사 생략" → 통과 ✅ (약 10초)
```

변경 여부는 첫 단계에서 `git diff --name-only origin/dev...HEAD`로 PR이 바꾼 파일 목록을 보고 판단합니다.

## 3. 예시 시나리오

| PR 내용 | backend | frontend | ai-server | docs |
| --- | --- | --- | --- | --- |
| README만 수정 | 건너뜀 ✅ | 건너뜀 ✅ | 건너뜀 ✅ | **실제 검사** |
| `ai_report` Entity + migration 추가 | **실제 검사** | 건너뜀 ✅ | 건너뜀 ✅ | 안 돎 |
| 화면 + API를 함께 수정 | **실제 검사** | **실제 검사** | 건너뜀 ✅ | 안 돎 |
| AI 프롬프트 수정 + 설계 문서 수정 | 건너뜀 ✅ | 건너뜀 ✅ | **실제 검사** | **실제 검사** |

## 4. 필수 검사와 job 이름

- 필수 검사 목록은 저장소 파일이 아니라 **GitHub 저장소 설정(ruleset)** 에 있습니다. 현재 `backend` · `frontend` · `ai-server`가 필수이고, `docs`는 필수가 아닙니다
- 필수 검사는 **job 이름**으로 등록됩니다. 그래서 job 이름을 서비스별로 다르게 지었습니다. 셋이 같은 이름이면 어느 검사인지 구분되지 않습니다
- job 이름(`name: backend`)을 바꾸면 ruleset도 같이 바꿔야 합니다. 안 그러면 옛 이름의 결과를 영영 기다립니다

## 5. 내 PR에서 결과 보는 법

1. PR 화면 아래쪽 **Checks** 영역에서 `backend` · `frontend` · `ai-server` · `docs` 상태를 봅니다
2. ❌인 검사의 **Details**를 누르면 단계별 로그가 나옵니다. 빨간 단계를 펼쳐 에러 문구를 찾습니다
3. 그 문구로 [06 문제 해결](06-troubleshooting.md)의 증상 표를 찾습니다
4. 고친 뒤 같은 브랜치에 push하면 CI가 자동으로 다시 돕니다

다음: [03 backend 검사](03-backend.md)
