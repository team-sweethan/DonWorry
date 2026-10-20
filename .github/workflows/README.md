# CI 워크플로

| 파일 | 잡 이름 | 검사 |
| --- | --- | --- |
| `backend.yml` | `backend` | `./gradlew check` — 컴파일 · ModularityTests(모듈 경계 · 순환) · ArchitectureTests(계층) |
| `frontend.yml` | `frontend` | `npm ci` · oxlint · Prettier `--check` · `tsc` + 빌드 |
| `ai-server.yml` | `ai-server` | `uv sync --locked` · ruff · pytest |

## 언제 도나

- **PR**: 세 개 모두 항상 실행 — 필수 검사가 "대기 중"으로 멈추지 않게
- **`main` · `dev` push**: 바뀐 폴더의 워크플로만 실행 (경로 필터)

## 설계 의도

- 잡 이름을 서비스별로 나눴다. 필수 검사(ruleset)는 이 이름으로 등록되므로 셋이 같으면 구분되지 않는다
- `./gradlew check`는 DB 없이 돈다. 클래스 구조만 검사한다
- `npm ci` · `uv sync --locked`는 잠금 파일(`package-lock.json` · `uv.lock`) 기준으로만 설치한다. 잠금 파일을 커밋하지 않으면 실패한다
- 테스트 파일은 `src/test`(backend) · `tests/`(ai-server)에 넣기만 하면 CI에 자동으로 포함된다. 워크플로를 고칠 필요가 없다

## 겪은 문제

- **`./gradlew: Permission denied`** — Windows에서 커밋하면 실행 권한이 빠진다. 워크플로에 `chmod +x gradlew`를 넣어 두었다
- **첫 backend CI가 5분 넘게 걸림** — 검사는 1분 이내이고, 나머지는 `setup-gradle`이 Gradle · 라이브러리 캐시를 업로드하는 시간("Post job cleanup")이다. 캐시가 생긴 뒤에는 빨라진다. `build.gradle` 의존성을 바꾸면 한 번 다시 길어진다
- **`uv.lock` 불일치로 실패** — `pyproject.toml`에 라이브러리를 추가했으면 `uv sync`(또는 `uv add`)로 갱신된 `uv.lock`도 함께 커밋한다

## 아직 없는 것

- DB가 필요한 검사(Flyway 전체 적용 + JPA `validate`) — ERD 확정 후 Flyway를 켤 때 추가
