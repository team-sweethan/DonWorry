package com.donworry;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

// 모듈 경계·순환 의존 검사 — DB 없이 돈다
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(DonWorryApplication.class);

    // 모듈 경계 · 순환 · 허용되지 않은 방향 의존을 검사한다
    @Test
    void modules_respectBoundariesAndAllowedDependencies() {
        modules.verify();
    }

    // 모듈 관계도(PlantUML)를 생성한다
    @Test
    void modules_writeDiagrams() {
        // build/spring-modulith-docs 에 모듈 다이어그램(PlantUML)이 생긴다
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
