package com.donworry.fds.controller;

import com.donworry.fds.api.FdsEvaluationDetail;
import com.donworry.fds.api.FdsEvaluationPage;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.service.FdsEvaluationNotFoundException;
import com.donworry.fds.service.FdsEvaluationQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fds/evaluations")
public class FdsEvaluationController {

    private final FdsEvaluationQueryService queryService;

    public FdsEvaluationController(FdsEvaluationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public FdsEvaluationPage list(
            @RequestParam(required = false) FdsResult result,
            @RequestParam(defaultValue = "false") boolean attentionOnly,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return queryService.list(result, attentionOnly, search, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/{evaluationId}")
    public FdsEvaluationDetail detail(@PathVariable Long evaluationId) {
        return queryService.detail(evaluationId);
    }

    @ExceptionHandler(FdsEvaluationNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
