// Investigation 모듈 (P3 수민) — Case 생성·병합·결제별 판정·금융 조치 인계
// 다른 모듈은 api 패키지만 사용한다
// 의존 허용: payment · fds · identity (report 포함) — 방향은 ADR-0012, ModularityTests가 검사
@org.springframework.modulith.ApplicationModule(
        displayName = "Investigation",
        allowedDependencies = {"payment :: api", "fds :: api", "identity :: api"})
package com.donworry.investigation;
