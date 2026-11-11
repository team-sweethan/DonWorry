package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.dto.AiSwitchChangeRequest;
import com.donworry.investigation.report.dto.AiSwitchStatusResponse;
import com.donworry.investigation.report.service.AiSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// AI 조사 긴급정지 (#626, ADR-0083) — 조회 · 끄기는 보안 직원 · ADMIN, 다시 켜기는 ADMIN
// 역할은 SecurityConfig가 막고, 서비스가 한 번 더 확인한다. 응답은 바뀐 뒤의 상태
@RestController
@RequestMapping("/api/ai-switch")
@RequiredArgsConstructor
public class AiSwitchController {

    private final AiSwitchService aiSwitchService;

    @GetMapping
    public AiSwitchStatusResponse status() {
        return aiSwitchService.status();
    }

    @PostMapping("/stop")
    public AiSwitchStatusResponse stop(@RequestBody AiSwitchChangeRequest request) {
        return aiSwitchService.stop(request.reason());
    }

    @PostMapping("/resume")
    public AiSwitchStatusResponse resume(@RequestBody AiSwitchChangeRequest request) {
        return aiSwitchService.resume(request.reason());
    }
}
