package com.donworry.fds.controller;

import com.donworry.fds.dto.CreateFdsBlocklistEntryFromEvaluationRequest;
import com.donworry.fds.service.FdsBlocklistService;
import com.donworry.fds.service.FdsEvaluationNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 경보 상세의 차단 후보 등록 (#603). 화면이 식별값 원문을 다루지 않도록 평가 ID로 받는다
@RestController
@RequiredArgsConstructor
public class FdsEvaluationBlocklistController {
    private final FdsBlocklistService service;

    @PostMapping("/api/fds/evaluations/{evaluationId}/blocklist-entries")
    public ResponseEntity<Void> add(
            @PathVariable Long evaluationId, @Valid @RequestBody CreateFdsBlocklistEntryFromEvaluationRequest request) {
        service.addFromEvaluation(evaluationId, request.identifierType(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(FdsEvaluationNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
