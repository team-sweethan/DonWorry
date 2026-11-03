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

    // controller는 repository를 직접 호출하지 않는다
    @Test
    void controller_doesNotAccessRepository() {
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

    // controller는 entity에 의존하지 않는다
    @Test
    void controller_doesNotDependOnEntity() {
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

    // entity는 controller와 service에 의존하지 않는다
    @Test
    void entity_doesNotDependOnControllerOrService() {
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
