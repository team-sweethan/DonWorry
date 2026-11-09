package com.donworry.payment.controller;

import com.donworry.payment.service.ExportOutOfScopeException;
import com.donworry.payment.service.ExportTooLargeException;
import com.donworry.payment.service.InsiderExportBlockedException;
import com.donworry.payment.service.PaymentTransactionNotFoundException;
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

    @ExceptionHandler(PaymentTransactionNotFoundException.class)
    ResponseEntity<Object> notFound(PaymentTransactionNotFoundException e) {
        return problem(404, "PAYMENT_TRANSACTION_NOT_FOUND", "결제를 찾을 수 없습니다.");
    }

    @ExceptionHandler(ExportTooLargeException.class)
    ResponseEntity<Object> tooLarge(ExportTooLargeException e) {
        return problem(
                400,
                "PAYMENT_TRANSACTIONS_EXPORT_TOO_LARGE",
                "내보낼 거래가 %,d행을 넘습니다. 기간 · 가맹점 조건을 좁혀 주세요.".formatted(e.maxRows()));
    }

    // 계정은 이미 정지 · 로그아웃됐다. 의심받는 사람에게 탐지 내용을 알리지 않게 문구는 중립으로 둔다 (#651)
    @ExceptionHandler(InsiderExportBlockedException.class)
    ResponseEntity<Object> insiderBlocked(InsiderExportBlockedException e) {
        return problem(403, "INSIDER_EXPORT_BLOCKED", "보안 확인이 필요해 계정 사용이 일시 정지되었습니다. 관리자에게 문의해 주세요.");
    }

    // 범위 밖은 업무 안내다 — 정지가 아니라 무엇을 하면 되는지 알려 준다 (#730)
    @ExceptionHandler(ExportOutOfScopeException.class)
    ResponseEntity<Object> outOfScope(ExportOutOfScopeException e) {
        return problem(
                403,
                "PAYMENT_TRANSACTIONS_EXPORT_OUT_OF_SCOPE",
                "담당 사건 고객의 거래만 내보낼 수 있습니다. 사건 상세의 [이 고객 거래 내역 보기]에서 내보내 주세요.");
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
