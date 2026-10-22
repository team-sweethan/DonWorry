# AGENTS.md

사람과 AI 도구가 이 저장소에서 따르는 규칙.

## 1. 정본 위치

### 작업별로 먼저 읽을 문서

하려는 작업에 해당하는 줄의 문서만 먼저 읽는다. `docs/` 전체나 긴 문서 전체를 미리 읽지 않는다.
그 문서와 현재 코드로 결정할 수 없을 때만 아래 "정본 목록"의 관련 문서를 더 읽는다.

| 하려는 작업 | 먼저 읽을 문서 |
| --- | --- |
| DB 테이블 추가·변경 | [`docs/flyway/05-guide.md`](docs/flyway/05-guide.md) 1·5·6절, [`docs/erd/`](docs/erd/README.md) (작업 순서는 이 파일 4절) |
| 다른 모듈 호출, 이벤트 발행·구독 | [`docs/modulith/04-dependency-and-events.md`](docs/modulith/04-dependency-and-events.md), [ADR-0012](docs/decisions/0012-payment-settlement-dependency.md) |
| 모듈 안 클래스·패키지 배치 | [`docs/modulith/03-module-structure.md`](docs/modulith/03-module-structure.md) |
| 코드 작성 (Lombok · `@Builder` · DTO · 에러 응답 · 트랜잭션 · 이름 · 테스트 작성 · 로그) | [`docs/conventions/02-code.md`](docs/conventions/02-code.md) — `합의` 항목은 따르고, `합의 전`은 초안이라 리뷰 대상이며, `보류`는 담당에게 묻는다 |
| 내 모듈 기능 개발 | `docs/<모듈>/`이 있으면 그 README (예: [`docs/payment/`](docs/payment/README.md)). 없으면 현재 코드 |
| AI 조사·FastAPI 연동 | [ADR-0009](docs/decisions/0009-ai-investigation-call-structure.md) (규칙은 이 파일 6절) |
| CI 실패 | [`docs/ci/06-troubleshooting.md`](docs/ci/06-troubleshooting.md) |
| Flyway·로컬 DB 오류 | [`docs/flyway/06-troubleshooting.md`](docs/flyway/06-troubleshooting.md) |
| 모듈 경계·의존 방향·저장 방식·외부 계약 변경 | [`docs/decisions/`](docs/decisions/README.md) (규칙은 이 파일 8절) |
| 코드·PR 리뷰 | 이 파일 맨 아래 Code Review Rules |

### 정본 목록

- FDS 연계 업무(평가 → HELD → Case · AI 조사 → 직원 판정 → 금융 조치 인계 · 접수 확인)의 흐름 · 상태값 · 모듈 간 통신 → Notion **fds 정본** 페이지 (01 업무분석 / 02 기획 / 03 설계). 기존 `1 상태값` · `2 API 명세서` 페이지는 기준이 아니다
- 정산 관리 업무(실제 CAPTURE 실행 · 매입 배치 · 수수료 · 정산)와 P5 · P6 금융 실행 상세 → **정본 작성 중**. fds 정본은 이 범위를 정하지 않는다 (fds 정본 §5.9 제외 · 설계 3.3 제외 범위). 확정되면 이 줄을 갱신한다
- ERD → Notion **팀페이지 / ERD** (P1~P6 담당별 페이지, 1차 MVP 14개 테이블). 저장소 요약은 [`docs/erd/`](docs/erd/README.md), 실제 컬럼 · 타입은 migration SQL이 기준이다. ERD를 바꿀 때는 해당 담당 페이지를 먼저 합의 · 수정한다. DB 원칙 · INSERT/UPDATE 예시는 fds 정본 **04 DB 설계 및 데이터 매핑** (구현 가이드)
- 1차 CakeShop 연동 저장소 → https://github.com/team-sweethan/cakeshop_refactoring (DonWorry 연동에 맞게 수정 중)
- Flyway 개념·사용법·로컬 DB 문제 해결 → [`docs/flyway/`](docs/flyway/README.md)
- 모듈별 상세 설계 → `docs/<모듈>/` (예: [`docs/payment/`](docs/payment/README.md))
- Spring Modulith 개념·모듈 구조·의존 방향·이벤트 → [`docs/modulith/`](docs/modulith/README.md)
- 설계 결정과 그 이유 → [`docs/decisions/`](docs/decisions/) (ADR)
- 팀 규칙이 어디에 있고 무엇이 자동 강제되는지 → [`docs/conventions/`](docs/conventions/README.md) (규칙 지도)
- DB 스키마 → `backend/src/main/resources/db/migration/`
- 실제로 돌아가는 검사 규칙 → `build.gradle`, `.oxlintrc.json`, `pyproject.toml`, `.github/workflows/`
- 담당 · 진행 상황 → GitHub Issue

Notion은 "왜"를, 저장소는 "실제로 어떤지"를 담는다. 둘이 어긋나면 임의로 한쪽을 고치지 말고 영향 범위를 적어 공유한다.

## 2. 결정됨 ≠ 구현됨 ≠ 검증됨

| 상태 | 뜻 |
| --- | --- |
| 결정됨 | Notion 또는 ADR에 적힘 |
| 구현됨 | 해당 파일이 Git에 있음 |
| 검증됨 | 명령을 돌려 통과한 결과가 있음 |

돌려보지 않은 것을 "완료"라고 쓰지 않는다. 확인 못 한 것은 PR의 "미검증"에 적는다.

## 3. 담당

| 모듈 | 담당 |
| --- | --- |
| `identity` | P1 주환 (+ frontend) |
| `fds` | P2 정후 |
| `investigation` | P3 수민 |
| `investigation/report` | P4 현규 (+ ai-server) |
| `payment` | P5 시은 |
| `settlement` | P6 민정 |

## 4. Backend 규칙

- 다른 모듈은 `api/` 패키지만 쓴다. `entity/` · `repository/` 직접 참조 금지
- 두 모듈이 서로 호출하면 안 된다 (순환). 역방향 알림은 커밋 후 이벤트로 보낸다
- 이벤트 타입은 두 모듈 중 상대가 import해도 되는 쪽(의존 화살표를 받는 쪽)의 `api/`에 둔다 ([ADR-0012](docs/decisions/0012-payment-settlement-dependency.md)). 받는 쪽은 같은 이벤트가 두 번 와도 한 번만 반영한다 (멱등)
- 모듈 간 의존 방향은 각 모듈 `package-info.java`의 `allowedDependencies`로 `ModularityTests`가 검사한다. 새 모듈 의존이 필요하면 ADR 개정(팀 합의) 후 목록에 추가한다. 테스트를 통과시키려고 목록부터 늘리지 않는다
- 여러 모듈 데이터를 합치는 조회 API는 위층 모듈(예: `investigation`)에 둔다. 아래층이 위층을 호출하게 만들지 않는다
- 같은 앱 안의 모듈끼리는 Java 호출. HTTP는 FastAPI · CakeShop과만
- 모듈 안에서는 Controller → Service → Repository. Controller는 Repository·Entity를 직접 쓰지 않는다
- 코드 모양(들여쓰기·줄바꿈·import 순서)은 손으로 맞추지 않는다. 커밋 전 `./gradlew spotlessApply` (palantir-java-format, [ADR-0013](docs/decisions/0013-code-formatter.md))
- 같은 소유 담당의 테이블끼리만 물리 FK를 건다. 소유 담당이 다르면 FK를 걸지 않는다 (ID 값만 저장하는 논리 참조, 존재 확인은 소유 담당의 공개 경계로)
  - 예외: `investigation` 모듈 안이라도 P4의 `ai_reports`와 P3 테이블(`security_cases` · `payment_decisions`) 사이는 논리 참조다 (fds 정본 04 §3.1 · §3.2)
- 참조되는 금융 · 조사 기록(결제 · 평가 · Case · 판정 · 인계 · 보고서)은 물리 삭제하지 않는다
- 테이블은 Flyway로만 바꾼다 (`ddl-auto: validate`). 이미 `dev`에 병합된 마이그레이션 파일은 고치지 않고 새 파일을 추가한다 (CI가 검사)
- 마이그레이션 파일은 직접 이름 짓지 않고 `./gradlew newMigration -Pdesc=<snake_case>`로 만든다. 버전은 생성 시각(한국 시간, `Asia/Seoul` 고정) `V<yyyyMMdd_HHmmss>__<설명>.sql` ([ADR-0011](docs/decisions/0011-flyway-migration-versioning.md))
- 한 마이그레이션은 하나의 배포 가능한 스키마 전환만 담는다. 서로 의존하는 마이그레이션은 같은 PR에 넣는다 (`out-of-order`는 순서를 보장하지 않는다)
- 업무 테이블은 소유 담당이 자기 마이그레이션으로 추가 · 변경한다 (1차 MVP 초기 14개 테이블만 Issue #56에서 일괄 생성). ERD(Notion 팀페이지 / ERD)에 없는 컬럼 · 테이블은 담당 페이지 합의 후 추가한다
- 테이블 이름은 복수, PK는 `BIGINT AUTO_INCREMENT`, 상태는 `VARCHAR`(EnumType.STRING), 시각은 UTC `DATETIME(6)`, 금액은 원 단위 `BIGINT` ([`docs/erd/` 1절](docs/erd/README.md#공통-규칙))
- 작업 순서·로컬 DB 초기화 → [`docs/flyway/05-guide.md`](docs/flyway/05-guide.md), 오류 해결 → [`docs/flyway/06-troubleshooting.md`](docs/flyway/06-troubleshooting.md)

### DB(테이블) 변경 작업 순서 — 사람·AI 공통

1. [`docs/flyway/05-guide.md`](docs/flyway/05-guide.md)를 먼저 읽는다
2. 기존 스키마는 `backend/src/main/resources/db/migration/*.sql`을 버전 순으로 전부 읽어 확인한다. 특정 테이블은 그 이름이 나오는 파일을 모두 찾아 `CREATE`와 이후 `ALTER`를 합쳐 본다
3. [`docs/erd/`](docs/erd/README.md)에서 내 소유 테이블·컬럼인지 확인한다. 문서에 없거나 비어 있으면 추측해서 만들지 말고 사람에게 확인한다. 다른 담당 소유 테이블이면 진행하지 않고 소유 담당 확인을 요청한다
4. `./gradlew newMigration -Pdesc=<snake_case>`로 파일 생성 → SQL 작성 → Entity 작성 (컬럼 이름·타입을 SQL에 맞춤)
5. `./gradlew check mariaDbTest`. 실행하지 못한 검사는 미검증으로 보고한다
6. Gradle을 실행할 수 없는 환경에서만 아래 명령으로 버전을 만들고, `newMigration`이 넣는 머리말 주석을 그대로 붙인다. 시간대 옵션을 빼면 UTC로 찍혀 순서가 어긋난다
   - bash: `TZ=Asia/Seoul date +%Y%m%d_%H%M%S`
   - PowerShell: `[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'Korea Standard Time').ToString('yyyyMMdd_HHmmss')`

## 5. Frontend 규칙

- HTTP 호출은 `src/api/`에만 둔다. 화면에서 직접 `fetch`하지 않는다
- `any`를 쓰지 않는다 (oxlint가 막는다)
- 결제 승인 · FDS 점수 · 권한 판정은 백엔드 몫이다. 화면 가드는 편의일 뿐 인가가 아니다
- CakeShop 화면은 CakeShop 저장소 소유다. 여기에 만들지 않는다

## 6. AI Server 규칙

- controller → service → agent 역할을 섞지 않는다
- Agent는 허용 목록에 있는 읽기 전용 Tool만 호출한다. 결제 승인 · 데이터 변경은 하지 않는다
- Spring을 부를 때는 `investigation/report`의 내부 Tool API만 쓴다. DB에 직접 접속하지 않는다 ([ADR-0009](docs/decisions/0009-ai-investigation-call-structure.md))
- 보고서 생성이 실패해도 자동으로 ACCEPT / REJECT 하지 않는다. 판정은 직원이 한다
- 보고서는 `report_version`별로 쌓고 덮어쓰지 않는다. 증거 출처 · 조회 시각은 `report_body` JSON 안에 둔다 (별도 `evidence_refs` 컬럼 없음)
- 요청 · 응답은 Pydantic 모델로 받고 내보낸다
- 코드 모양은 손으로 맞추지 않는다. 커밋 전 `uv run ruff format .`
- LLM 호출은 `LlmClient` 경계를 지난다. 제공자를 바꿀 때 service를 고치지 않는다

## 7. 작업 순서

브랜치: `feature/*` → `dev` (개발 통합) → `main` (시연·배포 기준)

1. `git status`, `git log --oneline -5`로 현재 위치 확인
2. Issue 확인 → `dev`에서 `feature/<이슈번호>-<작업>` 브랜치 생성. 이슈는 [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/)의 작업 · 버그 템플릿으로 쓰고, 이슈의 "완료 기준"을 PR "테스트 결과"에서 확인한다
3. 테스트 결과를 볼 때는 어느 커밋에서 나온 결과인지 확인한다. 오래된 결과를 현재 상태로 믿지 않는다
4. 문서와 코드가 충돌하면 고치기 전에 영향 범위부터 공유한다
5. 바꾼 영역의 검사 명령 실행
6. `dev`로 PR — 템플릿의 "미검증" 칸을 비우지 않는다
7. `dev` → `main` 병합은 시연·마일스톤 때만 (팀 합의)
8. 작업을 마치면 이 형식으로 보고한다 (사람·AI 공통, PR 본문과 같은 항목)
   - 변경 파일과 무엇을 바꿨는지
   - 실행한 검사 명령과 결과
   - 실행하지 못한 검사 (미검증)
   - 문서·코드에 답이 없어 사람이 정해야 할 것, 다른 담당 계약에 닿는 것

`main` · `dev`에 직접 push하지 않는다.

## 8. 설계를 바꿀 때

모듈 경계 · 의존 방향 · 저장 방식 · 외부 계약 · 기술 선택을 바꾸면 [`docs/decisions/`](docs/decisions/)에 ADR을 남긴다.
버린 대안과 이유를 반드시 적고, 영향 범위(API · DB · 화면 · 테스트)를 같은 PR에서 함께 고친다.

## 9. 검사 명령

```bash
cd backend   && ./gradlew spotlessApply              # 코드 모양 자동 정리 (check 전에)
cd backend   && ./gradlew check                      # 컴파일 + 코드 모양 + ModularityTests(순환·내부 접근) + ArchitectureTests(계층)
cd backend   && ./gradlew mariaDbTest                # Docker 필요 — MariaDB 컨테이너에 Flyway 전체 적용 + JPA validate
cd frontend  && npm run lint && npm run format:check && npm run build
cd ai-server && uv run ruff format . && uv run ruff check . && uv run pytest
```

CI가 무엇을 하는지와 자주 겪는 문제는 [`docs/ci/`](docs/ci/README.md).

## 10. AI 도구를 쓸 때

- 지시서만으로 규칙이 지켜진다고 보지 않는다. 실제 강제는 CI와 리뷰다
- 작업 완료 표시를 그대로 믿지 않는다. 코드 · Git · 검증 결과로 대조한다
- 돌려보지 않은 것을 "완료"라고 쓰지 않는다
- 모르는 것은 추측하지 않고 모른다고 적는다

## Code Review Rules
### Review Output Language (Mandatory)

- All GitHub Pull Request code review comments MUST be written in Korean (한국어).
- Review finding titles, descriptions, explanations, summaries, and suggested fixes MUST be written in Korean.
- Do NOT write review findings in English.
- Preserve original code identifiers, file paths, API names, commands, and error messages.
- Priority labels such as P0, P1, P2, and P3 may remain in English.
- These language rules apply to both automatic Codex reviews and reviews triggered by `@codex review`.

- 리뷰 제목과 설명은 한국어로 작성한다. 코드 식별자, 파일 경로, 명령어와 오류 메시지는 원문을 유지한다.
- 리뷰만 요청받은 경우 검토와 검증을 수행한다. 별도 수정 요청 없이 7절의 브랜치 생성·구현·PR 생성 절차를 진행하지 않는다.
- PR 변경과 관련 코드·테스트를 확인하고, 이번 변경이 새로 만들거나 악화한 결함과 적용 가능한 기존 규칙 위반을 중심으로 지적한다. 무관한 기존 문제나 취향에 따른 스타일 변경·리팩터링은 요구하지 않는다.
- 각 지적에는 문제 위치, 발생 조건, 영향과 수정 방향을 설명한다. 저장소 규칙 위반을 지적할 때는 해당 절을 근거로 들고, 8절에 따른 설계 변경 근거도 함께 확인한다.
- 확인하지 못한 Notion 내용이나 미확정 ERD를 추정하여 위반으로 판단하지 않는다. 4절의 Flyway 마이그레이션 규칙을 기준으로 판단한다. 문서와 코드가 충돌하면 근거와 영향 범위를 설명한다.
- 검증 결과는 2·7·10절에 따라 검토 대상 커밋의 실행 근거로 판단한다. 실행하지 못한 검사는 미검증으로 표시하고, 코드만 읽고 테스트가 통과했다고 쓰지 않는다.
