package com.donworry.investigation.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.investigation.report.client.AiServerProperties;
import java.time.Duration;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// 서비스 키 확인 규칙만 DB 없이 본다 — 맞는 키만 통과, Spring 키 설정이 비면 전부 거절
class AiServerKeyGuardTests {

    @Test
    void check_withSameKey_passes() {
        assertThatCode(() -> guard("secret").check("secret")).doesNotThrowAnyException();
    }

    @Test
    void check_withMissingOrWrongKey_isUnauthorized() {
        assertUnauthorized(() -> guard("secret").check(null));
        assertUnauthorized(() -> guard("secret").check("wrong"));
        assertUnauthorized(() -> guard("secret").check(""));
    }

    // 키 설정을 빠뜨린 채 켜도 빈 키끼리 통과하지 않는다
    @Test
    void check_whenKeyNotConfigured_rejectsEverything() {
        assertUnauthorized(() -> guard("").check(""));
        assertUnauthorized(() -> guard(" ").check(" "));
        assertUnauthorized(() -> guard("").check("anything"));
    }

    private static AiServerKeyGuard guard(String apiKey) {
        return new AiServerKeyGuard(new AiServerProperties(
                true,
                "http://localhost:8000",
                apiKey,
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofMinutes(5)));
    }

    private static void assertUnauthorized(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
