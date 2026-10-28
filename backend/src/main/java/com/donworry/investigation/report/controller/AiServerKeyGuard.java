package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.client.AiServerProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// ai-server가 부르는 /internal/ai/** 의 서비스 키 확인 (ADR-0009 결정 1) — 키가 없거나 틀리면 401
// P1 보안 체인에 /internal/ai/** 검문소가 생기면 그쪽으로 옮기고 이 클래스는 지운다
@Component
@RequiredArgsConstructor
class AiServerKeyGuard {

    private final AiServerProperties properties;

    // Spring에 키 설정이 비어 있으면 전부 거절한다 — 설정을 빠뜨려도 문이 열리지 않게
    void check(String givenKey) {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank() || givenKey == null || !matches(apiKey, givenKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI 서버 인증이 필요합니다.");
        }
    }

    // 응답 시간 차이로 키를 한 글자씩 알아내지 못하게 끝까지 비교한다
    private static boolean matches(String apiKey, String givenKey) {
        return MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8), givenKey.getBytes(StandardCharsets.UTF_8));
    }
}
