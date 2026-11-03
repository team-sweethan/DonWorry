package com.donworry.fds.controller;

import com.donworry.fds.service.FdsDemoErrorScenario;
import com.donworry.fds.service.FdsDemoErrorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 화면에는 노출하지 않으며 로컬 시연 스크립트만 다음 평가의 ERROR를 요청한다.
@RestController
@Profile("!prod")
@ConditionalOnProperty(prefix = "fds.demo-error", name = "enabled", havingValue = "true")
@RequestMapping("/api/demo/fds-errors")
public class FdsDemoErrorController {

    private final FdsDemoErrorService demoError;

    public FdsDemoErrorController(FdsDemoErrorService demoError) {
        this.demoError = demoError;
    }

    @PostMapping("/next")
    public ResponseEntity<Void> armNextEvaluation(@RequestParam FdsDemoErrorScenario scenario) {
        demoError.armNextEvaluation(scenario);
        return ResponseEntity.noContent().build();
    }
}
