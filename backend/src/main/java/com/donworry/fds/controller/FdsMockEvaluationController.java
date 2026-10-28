package com.donworry.fds.controller;

import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.service.FdsMockEvaluationService;
import com.donworry.fds.service.FdsMockFailureService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 개발 환경에서만 켜는 화면 시연용 Mock 평가 생성 API다.
@RestController
@RequestMapping("/api/fds/evaluations/mock")
@ConditionalOnProperty(prefix = "fds.mock", name = "seed-enabled", havingValue = "true", matchIfMissing = true)
public class FdsMockEvaluationController {

    private final FdsMockEvaluationService mockEvaluationService;
    private final FdsMockFailureService mockFailureService;

    public FdsMockEvaluationController(
            FdsMockEvaluationService mockEvaluationService, FdsMockFailureService mockFailureService) {
        this.mockEvaluationService = mockEvaluationService;
        this.mockFailureService = mockFailureService;
    }

    @PostMapping
    public List<FdsEvaluationResponse> create() {
        return mockEvaluationService.createMockEvaluations();
    }

    @PostMapping("/error")
    public FdsEvaluationResponse createError() {
        return mockFailureService.createMockError();
    }

    @PostMapping("/error/reevaluate")
    public FdsEvaluationResponse reevaluateError() {
        return mockFailureService.reevaluateMockError();
    }
}
