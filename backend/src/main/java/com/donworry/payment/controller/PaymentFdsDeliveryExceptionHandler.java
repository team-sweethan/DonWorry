package com.donworry.payment.controller;

import com.donworry.payment.service.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = PaymentFdsDeliveryController.class)
public class PaymentFdsDeliveryExceptionHandler extends ResponseEntityExceptionHandler {
    private ResponseEntity<Object> problem(int status, String code, String detail, String reason) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        body.setProperty("code", code);
        if (reason != null) body.setProperty("reasonCode", reason);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @ExceptionHandler(PaymentFdsDeliveryController.InvalidRequestException.class)
    ResponseEntity<Object> invalid(Exception e) {
        return problem(400, "FDS_RECOVERY_DELIVERY_INVALID_REQUEST", "결제 ID와 요청 형식을 확인해주세요.", null);
    }

    @ExceptionHandler(PaymentOperationsException.class)
    ResponseEntity<Object> missing(Exception e) {
        return problem(404, "PAYMENT_OPERATIONS_NOT_FOUND", "결제를 찾을 수 없습니다.", null);
    }

    @ExceptionHandler(PaymentFdsDeliveryNotAllowedException.class)
    ResponseEntity<Object> notAllowed(PaymentFdsDeliveryNotAllowedException e) {
        return problem(
                409,
                "FDS_RECOVERY_DELIVERY_NOT_ALLOWED",
                "현재 상태에서는 전달 복구할 수 없습니다.",
                e.getReason().name());
    }

    @ExceptionHandler({
        PaymentFdsDeliveryBusyException.class,
        java.util.concurrent.RejectedExecutionException.class,
        DataAccessException.class,
        CannotCreateTransactionException.class,
        TransactionTimedOutException.class
    })
    ResponseEntity<Object> unavailable(Exception e) {
        return problem(503, "FDS_RECOVERY_DELIVERY_UNAVAILABLE", "전달 복구를 처리할 수 없습니다. 상태 확인 후 다시 요청해주세요.", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated(Exception e) {
        return problem(401, "AUTH_001", "로그인이 필요합니다.", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(Exception e) {
        return problem(403, "AUTH_002", "접근 권한이 없습니다.", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        return problem(500, "FDS_RECOVERY_DELIVERY_INTERNAL_ERROR", "전달 복구를 처리할 수 없습니다.", null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var result = ProblemDetail.forStatusAndDetail(status, "요청 형식을 확인해주세요.");
        result.setProperty(
                "code",
                status.is5xxServerError()
                        ? "FDS_RECOVERY_DELIVERY_INTERNAL_ERROR"
                        : "FDS_RECOVERY_DELIVERY_INVALID_REQUEST");
        var safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(result, safeHeaders, status);
    }
}
