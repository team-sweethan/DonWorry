# 05. 검사 — `verify()`가 막는 것과 못 막는 것

[← Modulith 문서 목차](README.md)

## 1. 어디서 검사하나

`backend/src/test/java/com/donworry/ModularityTests.java`

```java
static final ApplicationModules modules = ApplicationModules.of(DonWorryApplication.class);

@Test
void modules_respectBoundariesAndAllowedDependencies() {
    modules.verify();
}
```

- `./gradlew check`에 포함되어 **DB 없이** 돕니다. PR마다 CI에서도 돕니다 ([CI 03](../ci/03-backend.md#modularitytests--모듈-사이-규칙-spring-modulith))
- 같은 클래스의 `modules_writeDiagrams()`가 `build/spring-modulith-docs/`에 모듈 관계도(PlantUML)를 만듭니다

## 2. 막는 것 / 못 막는 것

| 상황 | 결과 | 누가 막나 |
| --- | --- | --- |
| 다른 모듈의 `entity` · `repository` · `service` 등 비공개 클래스 import | ✅ 실패 | `verify()` |
| 두 모듈이 서로 import (순환) | ✅ 실패 | `verify()` |
| **허용되지 않은 방향** import (예: `fds`가 `investigation.api`를 import) | ✅ 실패 | `verify()` + 각 모듈의 `allowedDependencies` (3절) |
| `investigation` 안에서 P3 코드가 P4 `report.repository` 사용 | ❌ 통과 (같은 모듈) | 리뷰 |
| 남의 테이블을 SQL로 직접 조회 (`JdbcTemplate`, native query) | ❌ 통과 — Java import가 없어서 Modulith가 모름 | 리뷰 |
| Controller가 Repository 직접 호출 | — (Modulith 범위 아님) | `ArchitectureTests` |

## 3. `allowedDependencies` — 모듈별 허용 의존

[ADR-0012](../decisions/0012-payment-settlement-dependency.md)의 방향을 각 모듈 `package-info.java`에 적어 `verify()`가 방향까지 검사합니다 (Issue #60).

```java
// identity/package-info.java
@ApplicationModule(displayName = "Identity", allowedDependencies = {})

// fds/package-info.java
@ApplicationModule(displayName = "Fds", allowedDependencies = {"identity :: api"})

// settlement/package-info.java  — ADR-0038에서 "identity :: api" 추가 (ADR-0012 보류 항목 확정)
@ApplicationModule(displayName = "Settlement", allowedDependencies = {"identity :: api"})

// payment/package-info.java
@ApplicationModule(displayName = "Payment",
        allowedDependencies = {"fds :: api", "identity :: api", "settlement :: api"})

// investigation/package-info.java  — investigation/report(P4)도 이 목록을 따른다
@ApplicationModule(displayName = "Investigation",
        allowedDependencies = {"payment :: api", "fds :: api", "identity :: api"})
```

- 빈 목록 `{}`은 "다른 모듈에 의존하지 않음"입니다. 속성을 아예 적지 않으면 제한이 없습니다 (Spring Modulith `ApplicationModule` Javadoc: "Declaring an empty array will allow no dependencies to other modules")
- `"fds :: api"`는 "fds 모듈의 `api`라는 이름의 공개 창구(`@NamedInterface("api")`)만 허용"이라는 뜻입니다
- 목록에 없는 모듈을 import하면 `ModularityTests`가 실패합니다. 새 의존이 필요하면 **ADR을 고치고(팀 합의) 목록에 추가**합니다
- 어느 모듈에도 속하지 않는 코드(`com.donworry` 바로 아래, 예: `DonWorryApplication`)는 목록과 상관없이 쓸 수 있습니다

### 검증

방향을 어기는 임시 클래스(`fds.service`에서 `investigation.api` import)를 넣고 `./gradlew check`를 돌려 실패를 확인한 뒤 지웠습니다.

```text
Module 'fds' depends on named interface(s) 'investigation :: api'
  via com.donworry.fds.service.TempWrongDirection -> com.donworry.investigation.api.TempCaseQuery.
  Allowed targets: identity :: api.
```

## 4. 실패했을 때

에러 메시지별 원인과 해결은 [CI 문제 해결 B3](../ci/06-troubleshooting.md#b3-modularitytests-실패)에 있습니다. 요약하면:

| 메시지 | 해결 방향 |
| --- | --- |
| `depends on non-exposed type` | 상대 모듈의 `api/`에 있는 인터페이스 · 이벤트만 쓴다. 없으면 담당자에게 `api/` 추가 요청 |
| `Cycle detected` | 화살표 반대 방향의 호출을 이벤트로 바꾼다 ([04 2절](04-dependency-and-events.md#2-동기-호출과-이벤트--언제-무엇을-쓰나)) |
| `Allowed targets: ...` | 허용되지 않은 방향의 의존 → 꼭 필요한지 확인하고, 필요하면 ADR 개정(팀 합의) 후 `allowedDependencies`에 추가 |
