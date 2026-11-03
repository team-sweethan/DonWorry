package com.donworry.payment.controller;

import java.time.DateTimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// 운영 조회 처리기와 같은 모양, 코드만 거래 내역용 — 운영 조회 오류 코드는 바꾸지 않는다
@RestControllerAdvice(assignableTypes = PaymentTransactionsController.class)
public class PaymentTransactionsExceptionHandler extends ResponseEntityExceptionHandler {
    private ResponseEntity<Object> problem(int status, String code, String detail) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    ResponseEntity<Object> invalid(Exception e) {
        return problem(400, "PAYMENT_TRANSACTIONS_INVALID_QUERY", "조회 조건을 확인해주세요. 기간은 최대 31일입니다.");
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<Object> unavailable(Exception e) {
        return problem(503, "PAYMENT_TRANSACTIONS_UNAVAILABLE", "거래 내역을 조회할 수 없습니다. 잠시 후 다시 확인해주세요.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        return problem(500, "PAYMENT_TRANSACTIONS_INTERNAL_ERROR", "거래 내역을 조회할 수 없습니다.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var result = ProblemDetail.forStatusAndDetail(status, "요청 형식을 확인해주세요.");
        result.setProperty(
                "code",
                status.is5xxServerError()
                        ? "PAYMENT_TRANSACTIONS_INTERNAL_ERROR"
                        : "PAYMENT_TRANSACTIONS_INVALID_QUERY");
        var safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(result, safeHeaders, status);
    }
}
