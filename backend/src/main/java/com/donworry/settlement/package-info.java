// Settlement 모듈 (P6 민정) — CAPTURE 실행·매입 배치·수수료·정산
// 다른 모듈은 api 패키지만 사용한다
// 다른 모듈에 의존하지 않는다 (identity 필요 시 ADR 개정 후 추가) — 방향은 ADR-0012, ModularityTests가 검사
@org.springframework.modulith.ApplicationModule(
        displayName = "Settlement",
        allowedDependencies = {})
package com.donworry.settlement;
