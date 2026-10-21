# AGENTS.md

사람과 AI 도구가 이 저장소에서 따르는 규칙.

## 1. 정본 위치

- FDS 연계 업무(평가 → HELD → Case · AI 조사 → 직원 판정 → 금융 조치 인계 · 접수 확인)의 흐름 · 상태값 · 모듈 간 통신 → Notion **fds 정본** 페이지 (01 업무분석 / 02 기획 / 03 설계). 기존 `1 상태값` · `2 API 명세서` 페이지는 기준이 아니다
- 정산 관리 업무(실제 CAPTURE 실행 · 매입 배치 · 수수료 · 정산)와 P5 · P6 금융 실행 상세 → **정본 작성 중**. fds 정본은 이 범위를 정하지 않는다 (fds 정본 §5.9 제외 · 설계 3.3 제외 범위). 확정되면 이 줄을 갱신한다
- ERD(FDS 연계 범위) → Notion **fds 정본 · 03 설계 §3.9** (Baseline v1, 8개 테이블). 정산 · 금융 실행 테이블은 미포함. DB 원칙 · INSERT/UPDATE 예시는 **04 DB 설계 및 데이터 매핑** (구현 가이드, 3.9를 대체하지 않음). ERD를 바꿀 때는 3.9를 먼저 합의 · 수정한다
- 1차 CakeShop 연동 저장소 → https://github.com/team-sweethan/cakeshop_refactoring (DonWorry 연동에 맞게 수정 중)
- Flyway 사용법·로컬 DB 문제 해결 → [`docs/flyway.md`](docs/flyway.md)
- 모듈별 상세 설계 → `docs/<모듈>/` (예: [`docs/payment/`](docs/payment/README.md))
- 설계 결정과 그 이유 → [`docs/decisions/`](docs/decisions/) (ADR)
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
- 이벤트 타입은 보내는 모듈의 `api/`에 둔다. 받는 쪽은 같은 이벤트가 두 번 와도 한 번만 반영한다 (멱등)
- 여러 모듈 데이터를 합치는 조회 API는 위층 모듈(예: `investigation`)에 둔다. 아래층이 위층을 호출하게 만들지 않는다
- 같은 앱 안의 모듈끼리는 Java 호출. HTTP는 FastAPI · CakeShop과만
- 모듈 안에서는 Controller → Service → Repository. Controller는 Repository·Entity를 직접 쓰지 않는다
- 같은 소유 담당의 테이블끼리만 물리 FK를 건다. 소유 담당이 다르면 FK를 걸지 않는다 (ID 값만 저장하는 논리 참조, 존재 확인은 소유 담당의 공개 경계로)
  - 예외: `investigation` 모듈 안이라도 P4의 `ai_report`와 P3 테이블(`security_case` · `payment_decision`) 사이는 논리 참조다 (fds 정본 04 §3.1 · §3.2)
- 참조되는 금융 · 조사 기록(결제 · 평가 · Case · 판정 · 인계 · 보고서)은 물리 삭제하지 않는다
- 테이블은 Flyway로만 바꾼다 (`ddl-auto: validate`). 이미 `dev`에 병합된 마이그레이션 파일은 고치지 않고 새 파일을 추가한다 (CI가 검사)
- 마이그레이션 파일은 직접 이름 짓지 않고 `./gradlew newMigration -Pdesc=<snake_case>`로 만든다. 버전은 생성 시각(UTC) `V<yyyyMMdd_HHmmss>__<설명>.sql` ([ADR-0011](docs/decisions/0011-flyway-migration-versioning.md))
- 한 마이그레이션은 하나의 배포 가능한 스키마 전환만 담는다. 서로 의존하는 마이그레이션은 같은 PR에 넣는다 (`out-of-order`는 순서를 보장하지 않는다)
- 업무 테이블은 소유 담당이 자기 마이그레이션으로 추가한다. ERD(설계 3.9)에 없는 컬럼·테이블은 3.9 합의 후 추가한다
- 작업 순서·로컬 DB 초기화·오류 해결 → [`docs/flyway.md`](docs/flyway.md)

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
- LLM 호출은 `LlmClient` 경계를 지난다. 제공자를 바꿀 때 service를 고치지 않는다

## 7. 작업 순서

브랜치: `feature/*` → `dev` (개발 통합) → `main` (시연·배포 기준)

1. `git status`, `git log --oneline -5`로 현재 위치 확인
2. Issue 확인 → `dev`에서 `feature/<이슈번호>-<작업>` 브랜치 생성
3. 테스트 결과를 볼 때는 어느 커밋에서 나온 결과인지 확인한다. 오래된 결과를 현재 상태로 믿지 않는다
4. 문서와 코드가 충돌하면 고치기 전에 영향 범위부터 공유한다
5. 바꾼 영역의 검사 명령 실행
6. `dev`로 PR — 템플릿의 "미검증" 칸을 비우지 않는다
7. `dev` → `main` 병합은 시연·마일스톤 때만 (팀 합의)

`main` · `dev`에 직접 push하지 않는다.

## 8. 설계를 바꿀 때

모듈 경계 · 의존 방향 · 저장 방식 · 외부 계약 · 기술 선택을 바꾸면 [`docs/decisions/`](docs/decisions/)에 ADR을 남긴다.
버린 대안과 이유를 반드시 적고, 영향 범위(API · DB · 화면 · 테스트)를 같은 PR에서 함께 고친다.

## 9. 검사 명령

```bash
cd backend   && ./gradlew check                      # 컴파일 + ModularityTests(순환·내부 접근) + ArchitectureTests(계층)
cd backend   && ./gradlew mariaDbTest                # Docker 필요 — MariaDB 컨테이너에 Flyway 전체 적용 + JPA validate
cd frontend  && npm run lint && npm run format:check && npm run build
cd ai-server && uv run ruff check . && uv run pytest
```

CI가 무엇을 하는지와 자주 겪는 문제는 [`.github/workflows/README.md`](.github/workflows/README.md).

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
