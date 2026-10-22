package com.donworry;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

// 모듈 안 계층 규칙 검사 — 모듈 사이 경계·순환은 ModularityTests 담당
// 아직 클래스가 없는 폴더도 통과하도록 allowEmptyShould(true)
class ArchitectureTests {

    static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.donworry");

    @Test
    void controller는_repository를_직접_호출하지_않는다() {
        noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..repository..")
                .because("Controller → Service → Repository 순서를 지킨다")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void controller는_entity에_의존하지_않는다() {
        noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..entity..")
                .because("화면·외부 응답은 DTO로 내보낸다")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void entity는_controller와_service에_의존하지_않는다() {
        noClasses()
                .that()
                .resideInAPackage("..entity..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..service..")
                .because("엔티티는 데이터만 담고 위 계층을 모른다")
                .allowEmptyShould(true)
                .check(classes);
    }
}
