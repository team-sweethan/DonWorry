package com.donworry.investigation.controller;

import com.donworry.investigation.dto.DemoHeldPaymentRequest;
import com.donworry.investigation.dto.DemoHeldPaymentResponse;
import com.donworry.investigation.service.DemoHeldPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 시연용 — P5 HELD 이벤트 연동 전까지 50~79점 HELD 결제를 직접 발생시킨다. 연동 후 지운다
@RestController
@RequestMapping("/api/demo/held-payments")
@RequiredArgsConstructor
public class DemoHeldPaymentController {

    private final DemoHeldPaymentService demoHeldPaymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DemoHeldPaymentResponse create(@Valid @RequestBody DemoHeldPaymentRequest request) {
        return demoHeldPaymentService.create(request);
    }
}
