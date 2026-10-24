package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.identity.dto.ChangePasswordRequest;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.PasswordChangeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTests {
    private final PasswordPolicy policy = new PasswordPolicy();

    // 영문·숫자와 함께 한글·특수문자·공백을 원문 그대로 허용한다
    @ParameterizedTest
    @ValueSource(strings = {"abcd1234", "Abcd1234!", "가나다abc12", " pass123 ", "abc123😀😀"})
    void validate_whenPolicySatisfied_accepts(String password) {
        assertThatCode(() -> policy.validate("user", password)).doesNotThrowAnyException();
    }

    // 누락·길이·영문 또는 숫자 누락·아이디와 같은 값은 거절한다
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abc1234", "abcdefgh", "12345678", "가나다라마123", "abcd1234"})
    void validate_whenPolicyViolated_rejects(String password) {
        assertThatThrownBy(() -> policy.validate("abcd1234", password))
                .isInstanceOfSatisfying(
                        PasswordChangeException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_NEW_PASSWORD));
    }

    // 문자 수와 UTF-8 바이트 한도를 각각 경계에서 검사한다
    @Test
    void validate_whenLengthAtBoundary_checksCharactersAndBytes() {
        policy.validate("user", "a1" + "x".repeat(62));
        assertThatThrownBy(() -> policy.validate("user", "a1" + "x".repeat(63)))
                .isInstanceOf(PasswordChangeException.class);
        policy.validate("user", "abc123" + "가".repeat(22));
        assertThatThrownBy(() -> policy.validate("user", "abc123x" + "가".repeat(22)))
                .isInstanceOf(PasswordChangeException.class);
        // 이모지는 UTF-16 두 글자가 아니라 코드 포인트 한 글자로 센다.
        assertThatThrownBy(() -> policy.validate("user", "a1" + "😀".repeat(5)))
                .isInstanceOf(PasswordChangeException.class);
        policy.validate("user", "a1" + "😀".repeat(6));
    }

    // DTO 문자열 표현에는 현재·새 비밀번호 원문이 포함되지 않는다
    @Test
    void request_whenConvertedToString_redactsPasswords() {
        assertThat(new ChangePasswordRequest("old-secret", "new-secret").toString())
                .doesNotContain("old-secret", "new-secret");
    }

    // UTF-8 변환 시 물음표로 대체되는 단독 서로게이트는 다른 비밀번호와 같은 해시가 되지 않게 거절한다
    @Test
    void validate_whenUnicodeMalformed_rejectsInsteadOfReplacingBytes() {
        assertThatThrownBy(() -> policy.validate("user", "abc123" + (char) 0xD800 + (char) 0xD800))
                .isInstanceOf(PasswordChangeException.class);
    }
}
