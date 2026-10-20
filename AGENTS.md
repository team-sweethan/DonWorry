# AGENTS.md

사람과 AI 도구가 이 저장소에서 따르는 규칙.

## 1. 정본 위치

- 상태값 · API 명세 · ERD → Notion (`1 상태값` / `2 API 명세서` / `3 ERD`)
- DB 스키마 → `backend/src/main/resources/db/migration/`
- 담당 · 진행 상황 → GitHub Issue

## 2. 담당

| 모듈 | 담당 |
| --- | --- |
| `identity` | P1 주환 (+ frontend) |
| `fds` | P2 정후 |
| `investigation` | P3 수민 |
| `investigation/report` | P4 현규 (+ ai-server) |
| `payment` | P5 시은 |
| `settlement` | P6 민정 |

## 3. Backend 규칙

- 다른 모듈은 `api/` 패키지만 쓴다. `entity/` · `repository/` 직접 참조 금지
- 두 모듈이 서로 호출하면 안 된다 (순환). 역방향 알림은 이벤트로 보낸다
- 같은 앱 안의 모듈끼리는 Java 호출. HTTP는 FastAPI · CakeShop과만
- 다른 모듈 테이블에 FK를 걸지 않는다 (UUID 칸만)
- 테이블은 Flyway로만 바꾼다. 이미 main에 들어간 마이그레이션 파일은 고치지 않고 새 번호를 추가한다
- 마이그레이션 번호는 PR 병합 직전에 main 최신 번호를 보고 정한다
- 현재는 ERD 확정 전이라 Flyway 꺼짐, `ddl-auto: update` 사용. 확정 후 위 Flyway 규칙 적용

## 4. 작업 순서

브랜치: `feature/*` → `dev` (개발 통합) → `main` (시연·배포 기준)

1. Issue 확인 → `dev`에서 `feature/<이슈번호>-<작업>` 브랜치 생성
2. 바꾼 영역의 검사 명령 실행
3. `dev`로 PR — 템플릿의 "미검증" 칸을 비우지 않는다
4. `dev` → `main` 병합은 시연·마일스톤 때만 (팀 합의)

`main` · `dev`에 직접 push하지 않는다.

## 5. 검사 명령

```bash
cd backend   && ./gradlew check                      # 컴파일 + 모듈 경계
cd frontend  && npm run lint && npm run format:check && npm run build
cd ai-server && uv run ruff check . && uv run pytest
```

## 6. AI 도구를 쓸 때

- 돌려보지 않은 것을 "완료"라고 쓰지 않는다
- 모르는 것은 추측하지 않고 모른다고 적는다
