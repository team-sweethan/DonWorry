package com.donworry.settlement.controller;

import com.donworry.settlement.service.SettlementRequestException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(
        assignableTypes = {
            PickupController.class,
            OwnerSettlementController.class,
            OwnerCashFlowController.class,
            OwnerRecurringExpenseController.class
        })
public class PickupExceptionHandler extends ResponseEntityExceptionHandler {
    private ResponseEntity<Object> problem(int status, String code, String detail) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @ExceptionHandler(SettlementRequestException.class)
    ResponseEntity<Object> business(SettlementRequestException e) {
        return problem(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> invalid(IllegalArgumentException e) {
        return problem(400, "SETTLEMENT_INVALID_REQUEST", "요청값을 확인해 주세요.");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> unavailable(DataAccessException e) {
        return problem(503, "SETTLEMENT_UNAVAILABLE", "처리 결과를 확인할 수 없습니다.");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Object> conflict(IllegalStateException e) {
        return problem(409, "SETTLEMENT_CONFLICT", "기존 기록과 충돌합니다. 담당자 확인이 필요합니다.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        return problem(500, "SETTLEMENT_INTERNAL_ERROR", "처리 중 오류가 발생했습니다.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(status.value(), "SETTLEMENT_INVALID_REQUEST", "요청 형식을 확인해 주세요.");
    }
}
