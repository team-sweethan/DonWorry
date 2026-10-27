# 07. Notion 자동 동기화 (`notion-sync`)

> **한 줄 요약:** GitHub에서 Issue · PR · ADR이 바뀌면 Notion "저장소 구현 현황 (자동)" 페이지가 따라 바뀝니다. 방향은 GitHub → Notion 하나뿐입니다.

Notion 워크스페이스는 한 사람이 소유하고 다른 팀원은 게스트라 각자 AI 도구를 Notion에 연결할 수 없습니다.
그래서 팀 공용인 GitHub에서 일이 생기면 Actions가 소유자의 Notion 연결 토큰으로 대신 기록합니다. 누가 PR을 병합해도 Notion이 갱신됩니다.

## 1. 무엇을 어디에 쓰나

| Notion 위치 | 원본 | 언제 | 키 |
| --- | --- | --- | --- |
| 작업 현황 (GitHub) DB | Issue · PR | Issue · PR 이벤트 즉시(그 건 + 최근 60분 안에 바뀐 건), `dev` 설정 · ADR push와 매일 03:00 KST에 전체 대조 | GitHub 번호 |
| ADR 현황 DB | [`docs/decisions/`](../decisions/README.md)의 `NNNN-*.md` | `dev`에 ADR이 병합될 때, 매일 전체 보정 | 파일 이름 |
| 상태 페이지 표의 "마지막 동기화" | 실행 시각 · 이벤트 · 커밋 | 매 실행 | — |
| 정본 페이지 맨 아래 자동 구역 | 관련 ADR · 담당의 최근 병합 PR · 저장소 문서 | `canonical_pages`를 설정했을 때만 | 🤖 callout |

- 담당(P1~P6)은 Assignee → 작성자 순서로 [`.github/notion-sync.json`](../../.github/notion-sync.json)의 `owners`에서 찾습니다. GitHub 계정이 바뀌면 여기를 고칩니다
- 유형은 제목 앞의 `feat:` · `[feat]` 형식에서 읽습니다
- 상태: Issue는 Open · Closed, PR은 Draft · In review · Merged · Closed
- 저장소에서 사라진 ADR 파일의 행은 보관(archive)합니다. Issue · PR 행은 지우지 않습니다
- 이벤트가 몰리면 GitHub가 대기 중인 실행을 가장 최근 것 하나만 남기고 취소합니다(`concurrency`). 그래서 이벤트 실행마다 최근 60분 안에 바뀐 Issue · PR을 함께 맞춥니다
- Issue · PR 행은 쓸 값(제목 · 상태 · 담당 · 라벨 등)이 Notion 행과 다를 때만 씁니다. 그래서 `owners`를 바꿔 `dev`에 병합하면 그 실행에서 담당이 달라진 행이 다시 쓰입니다

## 2. 하지 않는 것

- **Notion → GitHub 방향은 없습니다.** Notion 자동 페이지를 직접 고치면 다음 실행 때 덮어써집니다
- **정본 본문(FDS 정본 · ERD · 기획 · 설계)은 고치지 않습니다.** 자동 구역은 페이지 맨 아래 🤖 callout 하나뿐이고, 그 안만 매번 교체합니다
- 정본과 저장소가 어긋나면 지금처럼 `notion-sync` 라벨 Issue를 남기고 담당이 정본을 직접 고칩니다 ([AGENTS.md 1절](../../AGENTS.md#어긋날-때-기준))

## 3. 비밀값 보호

`NOTION_TOKEN`은 워크스페이스 소유자의 Notion 연결 토큰입니다. 다른 브랜치에서 고친 스크립트가 이 값을 쓰지 못하게 합니다.

- PR 이벤트는 `pull_request`가 아니라 `pull_request_target`으로 받습니다. 기본 브랜치(`dev`)의 스크립트가 실행되고, PR 코드는 체크아웃하거나 실행하지 않습니다(이벤트 payload만 읽음)
- 수동 실행(Run workflow)은 `dev`에서만 돕니다. 다른 브랜치를 고르면 job이 건너뛰어집니다
- 그래서 스크립트 · 워크플로를 고친 내용은 `dev`에 병합된 뒤부터 적용됩니다. PR에서 미리 돌려 보려면 5절처럼 로컬 `--dry-run`을 씁니다

## 4. 처음 설정 (워크스페이스 소유자 1회)

1. <https://www.notion.so/profile/integrations> 에서 **내부(Internal) 연결**을 만듭니다. 기능은 콘텐츠 읽기 · 업데이트 · 삽입만 켭니다
2. Notion "2차 프로젝트 / 저장소 구현 현황 (자동)" 페이지 → `•••` → 연결 → 방금 만든 연결 추가 (하위 DB 두 개도 함께 허용됩니다)
3. GitHub 저장소 Settings → Secrets and variables → Actions → `NOTION_TOKEN`에 연결의 토큰 저장 (저장소 관리자 권한 필요)
4. Actions 탭 → `notion-sync` → Run workflow. 처음에는 `dry_run`을 켜서 바뀔 내용을 로그로 확인한 뒤 끄고 다시 실행합니다
5. 정본 자동 구역을 쓰려면 해당 정본 페이지에도 2번처럼 연결을 추가하고 `canonical_pages`에 등록합니다

```json
"canonical_pages": [
  {
    "name": "fds 정본",
    "page_id": "400ee45ba5e180229232fedab508abd9",
    "owner": "P3 수민",
    "adrs": ["0019", "0027", "0034"],
    "paths": ["docs/investigation/README.md"],
    "recent_prs": 5
  }
]
```

`NOTION_TOKEN`이 없으면(설정 전) 워크플로는 아무것도 쓰지 않고 성공으로 끝납니다. 필수 검사가 아니라 병합을 막지 않습니다.

## 5. 로컬에서 확인

```bash
# 전체 대조(push)를 흉내 내기 — Issue · PR 목록을 GitHub에서 읽고 ADR을 파싱한다 (네트워크 필요)
GITHUB_EVENT_NAME=push python .github/scripts/notion_sync.py --dry-run

# 웹훅 payload 하나를 흉내 내기 — 그 건 + 최근 60분 안에 바뀐 Issue · PR
GITHUB_EVENT_NAME=pull_request_target GITHUB_EVENT_PATH=pr.json GITHUB_TOKEN=$(gh auth token) python .github/scripts/notion_sync.py --dry-run
```

`GITHUB_TOKEN`은 없어도 됩니다(공개 저장소라 토큰 없이 조회되지만 시간당 60회 제한). 토큰이 틀리면 최근 변경 조회가 실패해 이벤트로 온 그 건만 처리합니다.
`NOTION_TOKEN` 없이 `--dry-run`을 쓰면 Notion을 읽지 않아 모든 행이 "생성"으로 나옵니다. `NOTION_TOKEN`을 넣고 `--dry-run`을 쓰면 Notion을 읽기만 하고 쓰기 요청은 로그로만 남깁니다.

## 6. 문제 해결

| 증상 | 원인 · 해결 |
| --- | --- |
| `401` · `unauthorized` | `NOTION_TOKEN`이 틀렸거나 연결이 삭제됨 → 4절 1·3번 다시 |
| `404` · `object_not_found` | 연결이 해당 페이지에 추가되지 않음 → 4절 2번 (정본 구역은 5번) |
| `validation_error` · 속성 이름 | Notion DB 속성 이름을 바꿈 → 스크립트의 `work_props` · `adr_props` 이름과 맞추기 |
| 담당이 비어 있음 | `owners`에 그 GitHub 계정이 없음 |
| 이벤트를 놓친 행 | 60분 안에 다른 이벤트가 오면 함께 맞춰지고, 아니면 매일 03:00 KST 전체 보정에서 맞춰짐. 바로 맞추려면 `dev`에서 Run workflow |
| Run workflow가 건너뛰어짐 | `dev`가 아닌 브랜치를 골랐음 → `dev`로 다시 실행 (3절) |
| 스크립트를 고쳤는데 PR 이벤트에 반영 안 됨 | `pull_request_target`은 `dev`의 스크립트를 쓴다. 병합 뒤부터 적용 (3절) |
