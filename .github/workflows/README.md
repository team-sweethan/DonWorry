# CI 워크플로

설명 문서는 **[docs/ci/](../../docs/ci/README.md)** 에 있습니다 — 개념, 언제 무엇이 도나, 워크플로별 검사, 문제 해결.

| 파일 | job 이름 (필수 검사 이름) | 검사 | 상세 |
| --- | --- | --- | --- |
| `backend.yml` | `backend` | (PR) migration 검사 → `./gradlew check` → `./gradlew mariaDbTest` | [03](../../docs/ci/03-backend.md) |
| `frontend.yml` | `frontend` | `npm ci` → oxlint → Prettier `--check` → `tsc` + 빌드 | [04](../../docs/ci/04-frontend.md) |
| `ai-server.yml` | `ai-server` | `uv sync --locked` → ruff → pytest | [05](../../docs/ci/05-ai-server-and-docs.md) |
| `docs.yml` | `docs` (필수 아님) | Markdown 상대 링크 검사 | [05](../../docs/ci/05-ai-server-and-docs.md) |

워크플로를 고칠 때 주의할 점

- job 이름(`name:`)을 바꾸면 GitHub ruleset의 필수 검사 이름도 같이 바꿔야 합니다 ([02 4절](../../docs/ci/02-overview.md#4-필수-검사와-job-이름))
- 필수 서비스 검사(`backend`·`frontend`·`ai-server`)의 PR 트리거에는 경로 필터(`paths`)를 넣지 않습니다. 필수 검사가 "대기 중"으로 남아 병합이 막힙니다. 필수가 아닌 `docs`는 문서 경로 필터를 사용합니다 ([02 2절](../../docs/ci/02-overview.md#왜-pr에서는-항상-시작하고-건너뛰나))
