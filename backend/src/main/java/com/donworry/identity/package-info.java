// Identity 모듈 (P1 주환) — 가맹점·직원 계정·권한·인증, 보안 신호 제공
// 다른 모듈은 api 패키지만 사용한다
// 다른 모듈에 의존하지 않는다 — 방향은 ADR-0012, ModularityTests가 검사
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {})
package com.donworry.identity;
