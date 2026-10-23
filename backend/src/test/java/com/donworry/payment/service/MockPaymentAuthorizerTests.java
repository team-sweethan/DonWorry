package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MockPaymentAuthorizerTests {
    private final MockPaymentAuthorizer authorizer = new MockPaymentAuthorizer();

    // 유효한 요청은 성공하며 같은 결제의 참조를 재현하고 다른 결제와 구분한다.
    @Test
    void authorize_withValidCommand_returnsDeterministicSuccess() {
        var command = new AuthorizationCommand(10L, 1000L);
        var result = authorizer.authorize(command);
        assertThat(result).isEqualTo(new AuthorizationResult(AuthorizationResult.Type.SUCCEEDED, "MOCK-AUTH-10", null));
        assertThat(authorizer.authorize(command)).isEqualTo(result);
        assertThat(authorizer.authorize(new AuthorizationCommand(11L, 1000L)).authorizationRef())
                .isNotEqualTo(result.authorizationRef());
        assertThatThrownBy(() -> authorizer.authorize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // 잘못된 결제 ID와 금액은 승인 전에 거절한다.
    @ParameterizedTest
    @MethodSource("invalidCommands")
    void command_whenInvalid_rejectsConstruction(Long id, Long amount) {
        assertThatThrownBy(() -> new AuthorizationCommand(id, amount)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidCommands() {
        return Stream.of(
                Arguments.of(null, 1L),
                Arguments.of(0L, 1L),
                Arguments.of(-1L, 1L),
                Arguments.of(1L, null),
                Arguments.of(1L, 0L),
                Arguments.of(1L, -1L));
    }

    // 성공·거절의 필드 조합을 위반하면 결과 객체를 만들 수 없다.
    @ParameterizedTest
    @MethodSource("invalidResults")
    void result_whenInconsistent_rejectsConstruction(AuthorizationResult.Type type, String ref, String code) {
        assertThatThrownBy(() -> new AuthorizationResult(type, ref, code)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidResults() {
        var success = AuthorizationResult.Type.SUCCEEDED;
        var decline = AuthorizationResult.Type.DECLINED;
        return Stream.of(
                Arguments.of(null, null, null),
                Arguments.of(success, null, null),
                Arguments.of(success, " ", null),
                Arguments.of(success, "a".repeat(101), null),
                Arguments.of(success, "ref", "code"),
                Arguments.of(decline, "ref", "code"),
                Arguments.of(decline, null, null),
                Arguments.of(decline, null, " "));
    }
}
