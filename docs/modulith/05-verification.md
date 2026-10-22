# 05. 검사 — `verify()`가 막는 것과 못 막는 것

[← Modulith 문서 목차](README.md)

## 1. 어디서 검사하나

`backend/src/test/java/com/donworry/ModularityTests.java`

```java
static final ApplicationModules modules = ApplicationModules.of(DonWorryApplication.class);

@Test
void 모듈_경계와_순환_의존을_검사한다() {
    modules.verify();
}
```

- `./gradlew check`에 포함되어 **DB 없이** 돕니다. PR마다 CI에서도 돕니다 ([CI 03](../ci/03-backend.md#modularitytests--모듈-사이-규칙-spring-modulith))
- 같은 클래스의 `모듈_관계도를_생성한다()`가 `build/spring-modulith-docs/`에 모듈 관계도(PlantUML)를 만듭니다

## 2. 지금 막는 것 / 못 막는 것

| 상황 | 지금 | `allowedDependencies` 적용 후 |
| --- | --- | --- |
| 다른 모듈의 `entity` · `repository` · `service` 등 비공개 클래스 import | ✅ 실패 | ✅ 실패 |
| 두 모듈이 서로 import (순환) | ✅ 실패 | ✅ 실패 |
| **허용되지 않은 방향** import (예: `fds`가 `investigation.api`를 import, 순환은 아님) | ❌ **통과** | ✅ 실패 |
| `investigation` 안에서 P3 코드가 P4 `report.repository` 사용 | ❌ 통과 (같은 모듈) | ❌ 통과 — 리뷰로 막음 |
| 남의 테이블을 SQL로 직접 조회 (`JdbcTemplate`, native query) | ❌ 통과 | ❌ 통과 — Java import가 없어서 Modulith가 모름. 리뷰로 막음 |
| Controller가 Repository 직접 호출 | — (Modulith 범위 아님) | — `ArchitectureTests`가 막음 |

즉 **지금은 방향(ADR-0012 그림)을 코드가 강제하지 않습니다.** 순환만 아니면 그림에 없는 화살표를 만들어도 통과합니다.

## 3. `allowedDependencies` 적용 계획

팀이 의존 방향에 합의하면, 각 모듈의 `package-info.java`에 허용 의존 목록을 적어 방향까지 강제합니다.

```java
// identity/package-info.java
@ApplicationModule(displayName = "Identity", allowedDependencies = {})

// fds/package-info.java
@ApplicationModule(displayName = "Fds", allowedDependencies = {"identity :: api"})

// settlement/package-info.java  — identity가 필요해지면 "identity :: api" 추가
@ApplicationModule(displayName = "Settlement", allowedDependencies = {})

// payment/package-info.java
@ApplicationModule(displayName = "Payment",
        allowedDependencies = {"fds :: api", "identity :: api", "settlement :: api"})

// investigation/package-info.java
@ApplicationModule(displayName = "Investigation",
        allowedDependencies = {"payment :: api", "fds :: api", "identity :: api"})
```

- 빈 목록 `{}`은 "어느 모듈도 의존하지 않음"입니다. 속성을 아예 적지 않으면 지금처럼 제한이 없습니다
- `"fds :: api"`는 "fds 모듈의 `api`라는 이름의 공개 창구(`@NamedInterface("api")`)만 허용"이라는 뜻입니다
- 적용 후 목록에 없는 모듈을 import하면 `ModularityTests`가 실패합니다. 새 의존이 필요하면 **ADR을 고치고(팀 합의) 목록에 추가**합니다
- 적용 PR에서 방향을 어기는 임시 클래스로 실패하는지 한 번 확인하고 지웁니다 ([ADR-0012 검증](../decisions/0012-payment-settlement-dependency.md#검증))

## 4. 실패했을 때

에러 메시지별 원인과 해결은 [CI 문제 해결 B3](../ci/06-troubleshooting.md#b3-modularitytests-실패)에 있습니다. 요약하면:

| 메시지 | 해결 방향 |
| --- | --- |
| `depends on non-exposed type` | 상대 모듈의 `api/`에 있는 인터페이스 · 이벤트만 쓴다. 없으면 담당자에게 `api/` 추가 요청 |
| `Cycle detected` | 화살표 반대 방향의 호출을 이벤트로 바꾼다 ([04 2절](04-dependency-and-events.md#2-동기-호출과-이벤트--언제-무엇을-쓰나)) |
| (적용 후) 허용되지 않은 의존 | 꼭 필요한 의존인지 확인 → 필요하면 ADR 개정 후 `allowedDependencies`에 추가 |
