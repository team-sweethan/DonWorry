package com.donworry.identity.service;

import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TemporaryPasswordService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();
    private final PasswordPolicy policy;

    public String generate(String loginId) {
        var value = new StringBuilder("Dw7");
        for (int i = 0; i < 17; i++) value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        String password = value.toString();
        policy.validate(loginId, password);
        return password;
    }
}
