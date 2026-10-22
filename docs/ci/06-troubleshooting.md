# 06. CI 문제 해결

[← CI 문서 목차](README.md)

**사용법:** PR의 Checks에서 ❌인 검사의 **Details**를 열고, 빨간 단계의 에러 문구를 아래 표에서 찾습니다. 항목은 모두 **증상 → 원인 → 해결** 순서입니다.

| 검사 | 로그·화면에 보이는 문구 | 원인 한 줄 | 바로가기 |
| --- | --- | --- | --- |
| 공통 | `Expected — Waiting for status to be reported` | 검사가 시작되지 않음 | [A1](#a1-검사가-대기-중에서-움직이지-않음) |
| 공통 | 첫 실행이 5분 이상 걸림 | Gradle 캐시 업로드 | [A2](#a2-첫-실행이-5분-넘게-걸림) |
| backend | `./gradlew: Permission denied` | Windows 커밋으로 실행 권한 빠짐 | [B1](#b1-gradlew-실행-권한-없음) |
| backend | `병합된 migration을 수정/삭제했습니다` · `migration 파일명이 규칙과 다릅니다` | migration 규칙 위반 | [B2](#b2-migration-검사-실패) |
| backend | `ModularityTests` 실패 (`non-exposed type`, `Cycle detected`) | 다른 모듈 내부 접근, 순환 의존 | [B3](#b3-modularitytests-실패) |
| backend | `ArchitectureTests` 실패 (`Architecture Violation`) | 모듈 안 계층 규칙 위반 | [B4](#b4-architecturetests-실패) |
| backend | `mariaDbTest` 실패 | SQL 오류, Entity ↔ 테이블 불일치 | [B5](#b5-mariadbtest-실패) |
| frontend | `npm ci` 실패 (`in sync`) | `package-lock.json` 미커밋 | [C1](#c1-npm-ci-실패) |
| frontend | `npm run lint` 실패 (`no-explicit-any` 등) | oxlint 규칙 위반 | [C2](#c2-lint-실패) |
| frontend | `Code style issues found` | Prettier 포맷 불일치 | [C3](#c3-formatcheck-실패) |
| ai-server | `The lockfile at uv.lock needs to be updated` | `uv.lock` 미커밋 | [D1](#d1-uvlock-불일치) |
| ai-server | `ruff check` 실패 (`F401`, `I001` 등) | ruff 규칙 위반 | [D2](#d2-ruff-실패) |
| docs | `깨진 링크:` | 없는 파일을 가리키는 링크 | [E1](#e1-깨진-링크) |

## A. 공통

### A1. 검사가 대기 중에서 움직이지 않음

**증상** PR 화면에 필수 검사가 `Expected — Waiting for status to be reported`로 남아 병합 버튼이 열리지 않습니다.

**원인과 해결**

| 원인 | 해결 |
| --- | --- |
| PR이 `dev`와 **충돌**(conflict) 상태 — 충돌이 있으면 GitHub이 PR 검사를 시작하지 않습니다 | 충돌을 해결하고 push |
| 워크플로 YAML 문법 오류 — Actions 탭에 실행 자체가 없거나 "Invalid workflow file" | 워크플로 파일 수정 |
| job 이름(`name: backend`)을 바꿨는데 ruleset의 필수 검사 이름은 그대로 | 이름을 되돌리거나 저장소 관리자가 ruleset 수정 |

### A2. 첫 실행이 5분 넘게 걸림

**증상** backend 검사가 처음 돌 때 5분 이상 걸립니다.

**원인** 검사 자체는 1분 이내입니다. 나머지는 `setup-gradle`이 Gradle·라이브러리 캐시를 업로드하는 시간(`Post job cleanup` 단계)입니다.

**해결** 기다리면 됩니다. 캐시가 생긴 뒤에는 빨라집니다. `build.gradle` 의존성을 바꾸면 캐시를 새로 만드느라 한 번 다시 길어집니다.

## B. backend

### B1. gradlew 실행 권한 없음

**증상**

```text
./gradlew: Permission denied
```

**원인** Windows에서 커밋하면 `gradlew` 파일의 실행 권한이 빠질 수 있습니다.

**해결** 워크플로에 `chmod +x gradlew` 단계가 이미 있어서 지금은 나지 않습니다. 다시 보이면 `backend.yml`에서 이 단계가 지워지지 않았는지 확인합니다.

### B2. migration 검사 실패

**증상** `migration 불변·이름 규칙 검사` 단계가 실패합니다.

```text
병합된 migration을 수정/삭제했습니다. 되돌리고 새 migration을 추가하세요 (./gradlew newMigration -Pdesc=...)
migration 파일명이 규칙과 다릅니다: V3__create_ai_report.sql (./gradlew newMigration -Pdesc=... 로 생성)
```

**해결** Flyway 문서에 단계별로 정리돼 있습니다.

- 병합된 migration 수정·삭제 → [Flyway 06 D2](../flyway/06-troubleshooting.md#d2-ci-병합된-migration을-수정삭제함)
- 파일명 규칙 위반 → [Flyway 06 D3](../flyway/06-troubleshooting.md#d3-ci-migration-파일명-규칙-위반)

### B3. ModularityTests 실패

**증상** `./gradlew check`에서 `ModularityTests > 모듈_경계와_순환_의존을_검사한다()`가 실패합니다. 메시지는 대략 이런 형태입니다.

```text
- Module 'investigation' depends on non-exposed type com.donworry.payment.entity.Payment within module 'payment'!
- Cycle detected: Slice payment -> Slice settlement -> Slice payment
```

**원인과 해결**

| 메시지 | 원인 | 해결 |
| --- | --- | --- |
| `depends on non-exposed type` | 다른 모듈의 `entity` · `repository` · `service` 등 **공개되지 않은 클래스**를 import | 그 모듈의 공개 `api/` 패키지에 있는 인터페이스·DTO만 씁니다. 필요한 것이 없으면 그 모듈 담당에게 `api/` 추가를 요청합니다 |
| `Cycle detected` | 두 모듈이 서로를 호출 (순환) | 아래층이 위층에 알릴 일은 **커밋 후 이벤트**로 보냅니다 ([AGENTS.md 4절](../../AGENTS.md#4-backend-규칙), [ADR-0012](../decisions/0012-payment-settlement-dependency.md)) |

로컬 확인: `backend/`에서 `./gradlew test --tests com.donworry.ModularityTests`

### B4. ArchitectureTests 실패

**증상**

```text
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside in a package '..controller..'
should depend on classes that reside in a package '..repository..', because Controller → Service → Repository 순서를 지킨다' was violated (1 times):
Method <com.donworry...SomeController.list()> calls method <com.donworry...SomeRepository.findAll()> ...
```

**원인** 모듈 안 계층 규칙 위반입니다. 마지막 줄에 **어느 클래스의 어느 줄**인지 나옵니다.

**해결**

| 위반 규칙 | 고치는 법 |
| --- | --- |
| Controller → Repository | Service 메서드를 만들어 Controller는 Service만 호출 |
| Controller → Entity | 응답·요청을 DTO로 바꿔 Controller에서 Entity를 쓰지 않게 |
| Entity → Controller · Service | Entity에서 위 계층 참조를 제거 (로직은 Service로) |

규칙 설명: [03 backend 3절](03-backend.md#architecturetests--모듈-안-계층-규칙-archunit)

### B5. mariaDbTest 실패

**증상** `./gradlew mariaDbTest` 단계가 실패합니다.

**해결**

1. 실패한 실행 화면의 **Artifacts**에서 `backend-test-reports`를 내려받습니다
2. `reports/tests/mariaDbTest/index.html`을 열어 에러를 봅니다
3. 문구에 따라 원인을 찾습니다

| 문구 | 원인 | 해결 |
| --- | --- | --- |
| `Migration ... failed` | migration SQL 오류 | SQL 수정 (병합 전 파일이므로 고쳐도 됨) |
| `Schema-validation: missing column` | Entity 컬럼이 migration에 없음 | [Flyway 06 A4](../flyway/06-troubleshooting.md#a4-entity에-있는-컬럼이-테이블에-없음) |
| `Migration checksum mismatch` | 적용된 migration 수정 | [Flyway 06 A3](../flyway/06-troubleshooting.md#a3-적용된-migration-파일이-바뀜) |

로컬에서 똑같이: Docker Desktop을 켜고 `backend/`에서 `./gradlew mariaDbTest`

## C. frontend

### C1. npm ci 실패

**증상**

```text
npm ci can only install packages when your package.json and package-lock.json are in sync
```

**원인** `package.json`에 라이브러리를 추가·변경했는데 `package-lock.json`을 커밋하지 않았습니다.

**해결** `frontend/`에서 `npm install` → 바뀐 `package-lock.json`을 커밋합니다.

### C2. lint 실패

**증상** `npm run lint` 단계에서 규칙 이름과 위치가 나옵니다.

```text
× typescript-eslint(no-explicit-any): Unexpected any. Specify a different type.
```

**해결**

| 규칙 | 고치는 법 |
| --- | --- |
| `no-explicit-any` | 실제 타입을 적습니다. 모르면 `unknown`으로 받고 검사해서 좁힙니다 |
| `rules-of-hooks` | hook(`useState` 등)을 조건문·반복문·일반 함수 밖, 컴포넌트 최상단으로 옮깁니다 |

로컬 확인: `frontend/`에서 `npm run lint`. 규칙 설명은 [04 frontend 3절](04-frontend.md#3-oxlint-규칙)에 있습니다.

### C3. format:check 실패

**증상**

```text
[warn] src/pages/CaseList.tsx
[warn] Code style issues found in the above file. Run Prettier with --write to fix.
```

**해결** `frontend/`에서 `npm run format`을 한 번 돌리고 바뀐 파일을 커밋합니다. 편집기의 "저장 시 Prettier 포맷"을 켜 두면 다시 나지 않습니다.

## D. ai-server

### D1. uv.lock 불일치

**증상**

```text
The lockfile at `uv.lock` needs to be updated, but `--locked` was provided.
```

**원인** `pyproject.toml`에 라이브러리를 추가했는데 `uv.lock`을 갱신·커밋하지 않았습니다.

**해결** `ai-server/`에서 `uv sync`(또는 처음부터 `uv add <패키지>`) → 바뀐 `uv.lock`을 함께 커밋합니다.

### D2. ruff 실패

**증상**

```text
F401 [*] `os` imported but unused
I001 [*] Import block is un-sorted or un-formatted
```

**해결** `ai-server/`에서 `uv run ruff check --fix .`를 돌립니다. `[*]`가 붙은 항목은 자동으로 고쳐집니다. 남은 것은 메시지를 보고 직접 고칩니다. 규칙 설명은 [05 ai-server](05-ai-server-and-docs.md#ruff-규칙)에 있습니다.

## E. docs

### E1. 깨진 링크

**증상**

```text
깨진 링크:
  docs/erd/README.md → ../flyway.md
```

**원인** 링크가 가리키는 파일이 없습니다. 파일을 옮기거나 이름을 바꾸고 링크를 안 고친 경우가 대부분입니다.

**해결** 화살표 왼쪽 문서의 링크를 실제 경로로 고칩니다. 로컬 확인은 저장소 루트에서 `python .github/scripts/check_md_links.py`입니다.
