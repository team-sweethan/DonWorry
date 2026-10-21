# CI 워크플로

| 파일 | 잡 이름 | 검사 |
| --- | --- | --- |
| `backend.yml` | `backend` | (PR) 병합된 migration 수정 금지 · 파일명 규칙 → `./gradlew check` — 컴파일 · ModularityTests(모듈 경계 · 순환) · ArchitectureTests(계층) → `./gradlew mariaDbTest` — MariaDB 11.8 컨테이너에 Flyway 전체 적용 + JPA `validate` |
| `frontend.yml` | `frontend` | `npm ci` · oxlint · Prettier `--check` · `tsc` + 빌드 |
| `ai-server.yml` | `ai-server` | `uv sync --locked` · ruff · pytest |
| `docs.yml` | `docs` | Markdown 상대 링크가 실제 파일을 가리키는지 (`.github/scripts/check_md_links.py`) |

## 언제 도나

- **PR**: `backend` · `frontend` · `ai-server`는 항상 시작하지만, 첫 단계에서 자기 폴더가 바뀌었는지 보고 안 바뀌었으면 나머지를 건너뛰고 약 10초 만에 통과한다
  - 경로 필터로 아예 안 돌리면 필수 검사가 "대기 중"으로 남아 병합이 막히기 때문
  - 예) README만 고친 PR → 세 개 모두 바로 통과, `docs`만 실제 검사
- **PR · push의 `docs`**: `.md` 파일이 바뀔 때만 실행. 필수 검사가 아니다
- **`main` · `dev` push**: 바뀐 폴더의 워크플로만 실행 (경로 필터)

## 설계 의도

- 잡 이름을 서비스별로 나눴다. 필수 검사(ruleset)는 이 이름으로 등록되므로 셋이 같으면 구분되지 않는다
- `./gradlew check`는 DB 없이 돈다. 클래스 구조만 검사한다
- `./gradlew mariaDbTest`는 Docker가 필요하다. `@Tag("mariadb")`가 붙은 테스트만 돌고, `check`(=`test`)에서는 빠진다. GitHub 러너에는 Docker가 있어 별도 설정이 없다
- `npm ci` · `uv sync --locked`는 잠금 파일(`package-lock.json` · `uv.lock`) 기준으로만 설치한다. 잠금 파일을 커밋하지 않으면 실패한다
- 테스트 파일은 `src/test`(backend) · `tests/`(ai-server)에 넣기만 하면 CI에 자동으로 포함된다. 워크플로를 고칠 필요가 없다

## 겪은 문제

- **`./gradlew: Permission denied`** — Windows에서 커밋하면 실행 권한이 빠진다. 워크플로에 `chmod +x gradlew`를 넣어 두었다
- **첫 backend CI가 5분 넘게 걸림** — 검사는 1분 이내이고, 나머지는 `setup-gradle`이 Gradle · 라이브러리 캐시를 업로드하는 시간("Post job cleanup")이다. 캐시가 생긴 뒤에는 빨라진다. `build.gradle` 의존성을 바꾸면 한 번 다시 길어진다
- **`migration 불변·이름 규칙 검사` 실패** — `dev`에 이미 병합된 `db/migration` 파일을 고쳤거나, 파일명을 직접 지었다. 수정은 되돌리고 `./gradlew newMigration -Pdesc=...`로 새 파일을 만든다
- **`mariaDbTest` 실패** — 실패한 run 페이지의 Artifacts에서 `backend-test-reports`를 받아 `reports/tests/mariaDbTest/index.html`을 본다. `Migration ... failed`면 SQL 오류, `Schema-validation: missing column`이면 migration에 Entity 컬럼이 빠진 것이다
- **`uv.lock` 불일치로 실패** — `pyproject.toml`에 라이브러리를 추가했으면 `uv sync`(또는 `uv add`)로 갱신된 `uv.lock`도 함께 커밋한다
