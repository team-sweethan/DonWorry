package com.donworry.identity.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class OperatorSecurityContractTests {
    // 공개 계약은 같은 모듈 내부의 엔티티에도 의존하지 않는다
    @Test
    void contracts_doNotDependOnIdentityInternals() {
        var classes = new ClassFileImporter()
                .importClasses(
                        OperatorAccessDenied.class,
                        OperatorAccessDeniedType.class,
                        OperatorAccountStatusReader.class,
                        OperatorAccountStatusSnapshot.class,
                        OperatorAccountStatus.class);
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.donworry.identity.entity..",
                        "com.donworry.identity.repository..",
                        "com.donworry.identity.service..")
                .check(classes);
    }

    // 이벤트 재전달용 JSON에서도 BIGINT 식별자와 UTC 시각 및 거절 종류를 보존한다
    @ParameterizedTest
    @EnumSource(OperatorAccessDeniedType.class)
    void event_whenSerializedAndRead_preservesContract(OperatorAccessDeniedType deniedType) {
        var event = OperatorAccessDenied.builder()
                .eventId(9007199254740993L)
                .userId(1L)
                .loginId("owner")
                .merchantId(2L)
                .httpMethod("GET")
                .path("/api/admin/users")
                .deniedType(deniedType)
                .occurredAt(Instant.parse("2026-11-04T05:00:00.123456Z"))
                .clientIp("2001:db8::1")
                .suspensionEligible(true)
                .build();
        var mapper = JsonMapper.builder().build();
        var json = mapper.writeValueAsString(event);
        assertThat(mapper.readValue(json, OperatorAccessDenied.class)).isEqualTo(event);
        assertThat(mapper.readTree(json).path("eventId").isIntegralNumber()).isTrue();
    }
}
