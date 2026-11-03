package com.donworry.payment.controller;

import com.donworry.payment.service.PaymentOperationsException;
import java.time.DateTimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = PaymentOperationsController.class)
public class PaymentOperationsExceptionHandler extends ResponseEntityExceptionHandler {
    private ResponseEntity<Object> problem(int status, String code, String detail) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    ResponseEntity<Object> invalid(Exception e) {
        return problem(400, "PAYMENT_OPERATIONS_INVALID_QUERY", "조회 조건을 확인해주세요.");
    }

    @ExceptionHandler(PaymentOperationsException.class)
    ResponseEntity<Object> missing(PaymentOperationsException e) {
        return problem(404, "PAYMENT_OPERATIONS_NOT_FOUND", "결제를 찾을 수 없습니다.");
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<Object> unavailable(Exception e) {
        return problem(503, "PAYMENT_OPERATIONS_UNAVAILABLE", "결제 정보를 조회할 수 없습니다. 잠시 후 다시 확인해주세요.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        return problem(500, "PAYMENT_OPERATIONS_INTERNAL_ERROR", "결제 정보를 조회할 수 없습니다.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var result = ProblemDetail.forStatusAndDetail(status, "요청 형식을 확인해주세요.");
        result.setProperty(
                "code",
                status.is5xxServerError() ? "PAYMENT_OPERATIONS_INTERNAL_ERROR" : "PAYMENT_OPERATIONS_INVALID_QUERY");
        var safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(result, safeHeaders, status);
    }
}
