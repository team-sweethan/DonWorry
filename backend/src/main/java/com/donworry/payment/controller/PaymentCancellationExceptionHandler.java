package com.donworry.payment.controller;

import com.donworry.payment.service.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = PaymentCancellationController.class)
public class PaymentCancellationExceptionHandler extends ResponseEntityExceptionHandler {
    private ResponseEntity<Object> problem(int status, String code, String detail) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @ExceptionHandler(PaymentHttpException.class)
    ResponseEntity<Object> business(PaymentHttpException e) {
        return problem(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
    ResponseEntity<Object> unavailable(Exception e) {
        return problem(503, "CANCELLATION_UNAVAILABLE", "처리 결과를 확인할 수 없습니다. 같은 요청 키로 확인해주세요.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        return problem(500, "CANCELLATION_INTERNAL_ERROR", "처리 결과를 확인할 수 없습니다. 같은 요청 키로 확인해주세요.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                status, status.is5xxServerError() ? "처리 결과를 확인할 수 없습니다." : "요청 형식을 확인해주세요.");
        problem.setProperty(
                "code", status.is5xxServerError() ? "CANCELLATION_INTERNAL_ERROR" : "INVALID_CANCELLATION_REQUEST");
        var safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(problem, safeHeaders, status);
    }
}
