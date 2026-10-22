# 01. CI 개념

[← CI 문서 목차](README.md)

## 1. 한 문장으로

**CI(Continuous Integration, 지속적 통합)는 PR마다 자동으로 도는 "검사 담당 팀원"입니다.**
코드를 올리면 깨끗한 PC에서 처음부터 빌드하고 테스트를 돌려, 통과한 코드만 `dev`에 합쳐지게 합니다.

## 2. CI가 없으면

```text
10:00  팀원 A: 내 PC에서는 잘 돌아감 → dev에 병합
         (실제로는 import 하나를 빠뜨림. A의 PC에는 예전 빌드 파일이 남아 있어서 몰랐음)
10:30  팀원 B·C·D·E·F: dev를 pull → 컴파일 실패
         → 다섯 명이 각자 원인을 찾느라 작업 중단
```

## 3. CI가 있으면

```text
10:00  팀원 A: PR 생성
10:01  CI: 빈 리눅스 PC에서 처음부터 빌드 → 컴파일 실패 ❌ → 병합 버튼 잠김
10:05  팀원 A: import 추가 후 push → CI 통과 ✅ → 병합
       → 다른 팀원은 깨진 코드를 받지 않음
```

| CI가 주는 것 | 설명 |
| --- | --- |
| "내 PC에선 됐는데" 제거 | 매번 **빈 PC**에서 처음부터 빌드하므로 내 PC에만 있는 파일·설정에 기대지 않는다 |
| 깨진 코드가 dev에 안 들어감 | 검사를 통과해야 병합할 수 있다 (필수 검사) |
| 규칙을 사람이 외우지 않아도 됨 | 모듈 경계, 계층, `any` 금지, migration 수정 금지 같은 규칙을 기계가 매번 확인 |
| 리뷰어가 본질에 집중 | 포맷·문법은 CI가 보고, 사람은 설계와 로직을 본다 |

## 4. GitHub Actions 용어

우리 CI는 GitHub이 제공하는 **GitHub Actions**로 돌아갑니다.

```text
workflow (backend.yml)                  ← 작업 파일 하나. 언제 무엇을 할지 정의
└── job (backend)                       ← 작업 단위. 이 이름이 PR 화면의 검사 이름이 됨
    ├── step: 코드 내려받기 (checkout)   ← 명령 한 줄 또는 미리 만들어진 동작 하나
    ├── step: Java 21 설치
    ├── step: ./gradlew check
    └── step: ./gradlew mariaDbTest
```

| 용어 | 뜻 | 우리 프로젝트에서 |
| --- | --- | --- |
| workflow | 실행 조건과 할 일을 적은 YAML 파일 | `.github/workflows/*.yml` 4개 |
| trigger (`on:`) | 언제 실행할지 | PR 생성·갱신, `dev`·`main` push |
| job | 하나의 PC에서 순서대로 도는 step 묶음 | `backend`, `frontend`, `ai-server`, `docs` |
| step | 명령 하나 (`run:`) 또는 미리 만든 동작 (`uses:`) | `./gradlew check`, `actions/checkout@v4` |
| runner | job을 실행하는 **일회용 리눅스 PC** | `ubuntu-latest`. 끝나면 사라짐 |
| artifact | runner가 사라지기 전에 남기는 결과 파일 | backend 실패 시 `backend-test-reports` |
| 필수 검사 | 통과해야 병합 버튼이 열리는 job | GitHub 저장소 설정(ruleset)에서 job 이름으로 등록 |

## 5. CI가 막는 것과 못 막는 것

| 막는 것 (자동) | 못 막는 것 (사람 몫) |
| --- | --- |
| 컴파일·빌드 오류, 테스트 실패 | 업무 로직이 설계와 맞는지 |
| 모듈 순환 의존, 다른 모듈 내부 접근, 허용되지 않은 의존 방향 | 같은 모듈 안 담당 경계(P3 ↔ P4), SQL로 남의 테이블 직접 조회 |
| Controller → Repository 직접 호출 같은 계층 위반 | 이름·구조가 읽기 좋은지 |
| 병합된 migration 수정, 규칙 밖 파일명 | ERD(Notion 팀페이지 / ERD)에 없는 테이블·컬럼 추가 |
| 포맷·lint 규칙 위반, `any` 사용 | 테스트가 **충분한지** (테스트가 없으면 통과함) |

CI 통과는 "규칙을 어기지 않았다"는 뜻이지 "올바르다"는 뜻이 아닙니다. 그래서 리뷰가 함께 필요합니다 ([AGENTS.md 10절](../../AGENTS.md#10-ai-도구를-쓸-때)).

다음: [02 언제 무엇이 도나](02-overview.md)
