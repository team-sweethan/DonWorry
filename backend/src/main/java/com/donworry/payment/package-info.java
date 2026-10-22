// Payment 모듈 (P5 시은) — 결제 접수·카드 승인·HELD·VOID·결제 상태
// 다른 모듈은 api 패키지만 사용한다
// 의존 허용: fds · identity · settlement — 방향은 ADR-0012, ModularityTests가 검사
@org.springframework.modulith.ApplicationModule(
        displayName = "Payment",
        allowedDependencies = {"fds :: api", "identity :: api", "settlement :: api"})
package com.donworry.payment;
