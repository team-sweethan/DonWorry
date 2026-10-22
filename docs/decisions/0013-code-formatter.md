# 0013. 코드 모양 자동 정리 도구 (Java: palantir-java-format, Python: ruff format)

- **상태:** 제안
- **날짜:** 2026-10-22
- **관련:** Issue #50
- **영향 받는 담당:** P1~P6 전원 (backend · ai-server 코드를 고치는 모든 PR)

## 배경

- frontend는 Prettier와 CI `format:check`로 코드 모양이 이미 강제된다. backend는 포매터가 없고, ai-server는 `ruff check`(규칙 검사)만 하고 모양 검사는 하지 않는다.
- 6명과 AI 도구가 같은 파일을 고친다. 모양 기준이 없으면 들여쓰기·줄바꿈 차이가 diff(변경 내역)에 섞여 리뷰와 충돌 해결이 어려워진다.
- Claude Code 공식 문서(Best practices)는 CLAUDE.md 같은 지침은 권고(advisory)라 어길 수 있고, 매번 지켜야 하는 것은 도구로 강제하라고 한다. 모양을 문서로 적는 대신 도구에 맡긴다.
- 현재 backend Java 파일은 52개, 모두 4칸 들여쓰기이고 120자를 넘는 줄은 없다 (100자 초과 13줄). 파일이 적은 지금 한 번에 맞추는 비용이 가장 작다.

## 결정

- **backend:** Spotless Gradle 플러그인 + palantir-java-format(4칸 들여쓰기, 한 줄 120자). `./gradlew check`에 `spotlessCheck`가 포함되어 모양이 다르면 CI가 실패한다. 커밋 전에 `./gradlew spotlessApply`로 맞춘다.
- **ai-server:** CI에 `uv run ruff format --check .`를 추가한다. 기존 `[tool.ruff]` 설정(한 줄 100자)을 그대로 쓴다.
- 도입 PR에서 backend · ai-server 기존 코드 전체를 한 번 정리한다. 모양 정리는 별도 커밋으로 분리한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| google-java-format (2칸, 100자) | 기존 52개 파일 전체가 바뀐다. IntelliJ 기본 들여쓰기(4칸)와 달라 팀원이 자동 정렬(`Ctrl+Alt+L`)을 할 때마다 4칸으로 돌아가 CI가 실패한다. 설정을 바꿀 수 없는 도구다 (공식 README) |
| google-java-format AOSP 모드 (4칸, 100자) | 4칸이라 IntelliJ와는 맞지만 100자를 넘는 기존 13줄이 바뀐다. 긴 메서드 체인·람다를 palantir보다 잘게 나눈다. 120자 기준인 palantir가 기존 코드를 덜 바꾼다 |
| Eclipse 포매터 + 설정 파일 | 설정 파일을 팀이 직접 관리해야 하고, 설정 항목이 많아 합의할 거리가 늘어난다 |
| `ratchetFrom 'origin/dev'` (바뀐 파일만 검사) | 당장은 기존 코드를 안 건드리지만, 나중에 누가 한 줄만 고쳐도 그 파일 전체가 정리되어 기능 변경과 모양 변경이 한 PR에 섞인다 |
| 포매터 없이 컨벤션 문서로 안내 | 사람·AI 모두 어길 수 있고 리뷰에서 모양 지적이 반복된다. 문서는 도구가 정할 수 없는 팀 선택에만 쓴다 (Issue로 별도 진행) |

## 영향

- API · DB · 화면: 없음
- 개발 환경: Gradle을 실행하는 JDK가 21이어야 한다 (`JAVA_HOME`). palantir 공식 README상 Java 21 문법 정리는 Java 21에서 실행될 때 지원된다. CI는 이미 Java 21
- 열린 브랜치: 도입 PR 병합 후 각자 브랜치를 최신 `dev` 위로 rebase하고 `./gradlew spotlessApply`를 한 번 실행한다
- 테스트: `./gradlew check`에 `spotlessCheck` 추가. ai-server CI에 `ruff format --check` 단계 추가
- 문서: AGENTS.md 4·6·9절, README "빌드와 테스트", `docs/ci/` 02·03·05·06

## 검증

검증됨 (Windows PC, Gradle 9.3.0):

- 정리 전 `./gradlew spotlessCheck` → `spotlessJavaCheck FAILED`, 5개 파일 위반 (main 2 · test 3). `check`에 포함되어 CI에서 막히는 것 확인
- `./gradlew spotlessApply check` → `BUILD SUCCESSFUL`. 5개 파일 +99 / -69줄, 모두 줄바꿈 위치 변경 (한 줄 enum 펼침, 긴 메서드 체인 분리, 빈 줄 1개 제거)
- ai-server (ruff 0.17.1, `uv.lock` 기준): `ruff format --check .` → `9 files already formatted` (바뀌는 파일 없음), `ruff check .` 통과, `pytest` 1 passed

미검증:

- Gradle을 JDK 17로 실행했을 때의 동작 (팀원 PC 환경)
- GitHub Actions에서의 실제 실행 (PR 생성 후 확인)
