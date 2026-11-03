package com.donworry.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MerchantConnectionController {
    // HMAC 인증 경계에서 현재 키의 연결 확인을 커밋한 뒤에만 도달한다.
    @PostMapping("/api/merchant-connections/check")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void check() {}
}
