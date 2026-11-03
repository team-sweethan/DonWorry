package com.donworry.identity.controller;

import com.donworry.identity.dto.MerchantApplicationRequests.*;
import com.donworry.identity.dto.MerchantApplicationResponses.*;
import com.donworry.identity.service.MerchantApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merchant-applications")
@RequiredArgsConstructor
public class MerchantApplicationController {
    private final MerchantApplicationService service;
    private final com.donworry.identity.service.InitialPasswordService initialPasswords;

    private final com.donworry.identity.service.ApplicationSettings settings;

    @GetMapping("/config")
    public Config config() {
        return new Config(settings.isEnabled());
    }

    @PostMapping("/initial-password-setups")
    public ResponseEntity<Void> setup(
            @Valid @RequestBody com.donworry.identity.dto.ApplicationReviewRequests.SetupRequest input) {
        initialPasswords.setup(input);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<Submission> submit(@Valid @RequestBody SubmitRequest input, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.submit(input, request));
    }

    @PostMapping("/email-verifications")
    public Verification verify(@Valid @RequestBody VerifyRequest input) {
        return service.verify(input);
    }

    @PostMapping("/email-verification-resends")
    public ResponseEntity<Void> resend(@Valid @RequestBody ResendRequest input, HttpServletRequest request) {
        service.resend(input, request);
        return ResponseEntity.accepted().build();
    }
}
