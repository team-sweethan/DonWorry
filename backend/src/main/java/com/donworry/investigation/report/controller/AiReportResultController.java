package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.dto.AiInvestigationResult;
import com.donworry.investigation.report.service.AiReportResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// ai-server 결과 콜백 수신 (ADR-0009 결정 1) — ai-server를 켰을 때만 경로가 생긴다
// 현재 시도가 아니거나 이미 끝난 보고서의 결과도 200으로 받고 반영하지 않는다 (ai-server가 다시 보내지 않게)
// 서비스 키가 없거나 틀리면 본문을 보기 전에 401로 돌려보낸다
@ConditionalOnProperty(name = "ai.server.enabled", havingValue = "true")
@RestController
@RequestMapping("/internal/ai/reports")
@RequiredArgsConstructor
public class AiReportResultController {

    private final AiReportResultService resultService;
    private final AiServerKeyGuard keyGuard;

    @PostMapping("/{reportId}/result")
    public void receive(
            @RequestHeader(name = AiServerClient.API_KEY_HEADER, required = false) String apiKey,
            @PathVariable Long reportId,
            @RequestBody AiInvestigationResult result) {
        keyGuard.check(apiKey);
        if (!String.valueOf(reportId).equals(result.reportId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "경로와 본문의 보고서 ID가 다르다");
        }
        resultService.receive(reportId, result);
    }
}
