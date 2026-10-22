// Fds 모듈 (P2 정후) — 위험 점수·ALLOW/REVIEW/DENY/ERROR·평가 근거
// 다른 모듈은 api 패키지만 사용한다
// 의존 허용: identity — 방향은 ADR-0012, ModularityTests가 검사
@org.springframework.modulith.ApplicationModule(
        displayName = "Fds",
        allowedDependencies = {"identity :: api"})
package com.donworry.fds;
