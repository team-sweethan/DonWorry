# Spring Modulith 문서

> **한 줄 요약:** DonWorry는 **앱 하나**(Spring Boot) 안을 담당자별 **모듈 5개**로 나누고, 모듈끼리는 정해진 창구(`api/` 패키지)와 이벤트로만 소통합니다. 이 규칙을 Spring Modulith가 테스트로 검사합니다.

## 상황별 바로가기

| 지금 상황 | 읽을 문서 |
| --- | --- |
| Modulith가 처음이다 | [01 개념](01-concept.md) → [02 채택 이유](02-why.md) |
| 내 모듈에 클래스를 어디 둬야 할지 모르겠다 | [03 모듈 구조](03-module-structure.md) |
| 다른 모듈을 호출해야 한다 / 이벤트를 보내야 한다 | [04 의존 방향과 이벤트](04-dependency-and-events.md) |
| `ModularityTests`가 실패했다 | [05 검사](05-verification.md) → [CI 문제 해결 B3](../ci/06-troubleshooting.md#b3-modularitytests-실패) |

## 문서 목록

| 문서 | 내용 |
| --- | --- |
| [01-concept.md](01-concept.md) | 모듈러 모놀리스란 — 모놀리스 · MSA와 비교 |
| [02-why.md](02-why.md) | 왜 채택했나 — 4주 · 6명 프로젝트에서의 선택 |
| [03-module-structure.md](03-module-structure.md) | 우리 모듈 5개와 패키지 규칙, `@ApplicationModule` · `@NamedInterface` |
| [04-dependency-and-events.md](04-dependency-and-events.md) | 의존 방향, 동기 호출 vs 이벤트, 이벤트 클래스 위치, 이벤트 발행 기록 |
| [05-verification.md](05-verification.md) | `verify()`가 막는 것과 못 막는 것, `allowedDependencies` 적용 계획 |

## 관련 문서

- 결정 기록: [ADR-0012 모듈 간 의존 방향](../decisions/0012-payment-settlement-dependency.md)
- 지켜야 하는 규칙: [AGENTS.md 4절](../../AGENTS.md#4-backend-규칙)
- CI에서의 검사: [CI 03 backend](../ci/03-backend.md)
