package com.donworry.identity.service;

import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.PasswordChangeException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PasswordPolicy {
    private static final Pattern LETTER = Pattern.compile("[a-zA-Z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    public void validate(String loginId, String password) {
        if (password == null) throw new PasswordChangeException(AuthErrorCode.INVALID_NEW_PASSWORD);
        int length = password.codePointCount(0, password.length());
        if (length < 8
                || length > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.codePoints().anyMatch(code -> code >= 0xD800 && code <= 0xDFFF)
                || !LETTER.matcher(password).find()
                || !DIGIT.matcher(password).find()
                || password.equals(loginId)) {
            throw new PasswordChangeException(AuthErrorCode.INVALID_NEW_PASSWORD);
        }
    }
}
