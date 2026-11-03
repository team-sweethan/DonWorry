# 05. ai-server · docs 검사

[← CI 문서 목차](README.md)

## 1. ai-server

워크플로: [`.github/workflows/ai-server.yml`](../../.github/workflows/ai-server.yml) · job 이름 `ai-server`

```text
변경 감지 ──(ai-server/ 변경 없음)──▶ 통과
   ▼
   Python 3.13 준비 → pip install uv
   ▼
① uv sync --locked   ← uv.lock 그대로 설치
   ▼
② uv run ruff check .          ← 코드 규칙 검사
   ▼
③ uv run ruff format --check . ← 코드 모양 검사
   ▼
④ uv run pytest                ← tests/ 아래 테스트 실행
```

| 단계 | 하는 일 | 실패하면 의심할 것 | 로컬에서 똑같이 (`ai-server/`에서) |
| --- | --- | --- | --- |
| ① `uv sync --locked` | 잠금 파일 기준 설치 | `pyproject.toml`만 고치고 `uv.lock`을 커밋 안 함 | `uv sync --locked` |
| ② ruff | lint 규칙 검사 | import 정렬, 안 쓰는 import, 오래된 문법 | `uv run ruff check .` → 고치기: `uv run ruff check --fix .` |
| ③ ruff format | 코드 모양 검사 (고치지 않고 다르면 실패) | `ruff format`을 안 돌림 | `uv run ruff format --check .` → 고치기: `uv run ruff format .` |
| ④ pytest | 테스트 실행 | 테스트 실패 | `uv run pytest` |

### `uv sync --locked`

npm의 `npm ci`와 같은 역할입니다. `uv.lock`(잠금 파일)과 `pyproject.toml`이 어긋나면 고치지 않고 실패합니다.
라이브러리를 추가할 때는 `uv add <패키지>`를 쓰면 두 파일이 함께 바뀝니다. **두 파일을 같이 커밋**합니다.

### ruff 규칙

설정: `ai-server/pyproject.toml`의 `[tool.ruff]` — 한 줄 100자, Python 3.13 기준

| 규칙 묶음 | 뜻 | 예 |
| --- | --- | --- |
| `E` | PEP 8 기본 스타일 오류 | 너무 긴 줄 |
| `F` | Pyflakes — 실제 버그 가능성 | 안 쓰는 import, 정의 안 된 이름 |
| `I` | import 정렬 | 표준 라이브러리 → 외부 → 내 코드 순서 |
| `UP` | 최신 문법으로 | `List[str]` 대신 `list[str]` |
| `B` | 버그가 나기 쉬운 패턴 | 기본 인자에 `[]` 같은 변경 가능한 값 |

`I` · `UP` 위반은 대부분 `--fix`로 자동 수정됩니다.

### pytest

- `ai-server/tests/` 아래 `test_*.py` 파일을 자동으로 찾아 돌립니다. **워크플로를 고칠 필요 없이** 파일을 넣기만 하면 CI에 포함됩니다
- 지금은 `/health` 응답을 확인하는 `test_health.py` 하나가 있습니다

## 2. docs

워크플로: [`.github/workflows/docs.yml`](../../.github/workflows/docs.yml) · job 이름 `docs` (필수 검사 아님)

```text
python .github/scripts/check_md_links.py
```

- 저장소 안 모든 `*.md`에서 Markdown 링크 문법으로 쓴 **상대 링크**(`https://`로 시작하지 않는 링크)가 실제 파일·폴더를 가리키는지 봅니다
- 외부 URL(`https://...`), 같은 문서 안 앵커(`#...`), 코드 블록 안 링크는 보지 않습니다
- **`#앵커`는 검사하지 않습니다.** `docs/flyway/05-guide.md#없는-제목`처럼 파일은 있고 제목이 틀린 링크는 통과합니다. 제목을 바꿀 때는 그 제목을 가리키는 링크를 직접 찾아 고칩니다
- 로컬에서 똑같이: 저장소 루트에서 `python .github/scripts/check_md_links.py`

다음: [06 문제 해결](06-troubleshooting.md)
