// Settlement 모듈 (P6 민정) — CAPTURE 실행·매입 배치·수수료·정산
// 다른 모듈은 api 패키지만 사용한다
// identity 공개 api만 의존한다 (인증된 가맹점 확인) — 방향은 ADR-0012·ADR-0038, ModularityTests가 검사
@org.springframework.modulith.ApplicationModule(
        displayName = "Settlement",
        allowedDependencies = {"identity :: api"})
package com.donworry.settlement;
