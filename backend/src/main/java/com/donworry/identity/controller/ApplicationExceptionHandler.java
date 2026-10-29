package com.donworry.identity.controller;

import com.donworry.identity.security.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = {MerchantApplicationController.class, ApplicationReviewController.class})
@Order(-1)
public class ApplicationExceptionHandler {
    @ExceptionHandler(ApplicationException.class)
    public ProblemDetail applicationFailed(ApplicationException exception) {
        return problem(exception.getErrorCode());
    }

    @ExceptionHandler({
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ProblemDetail invalidInput(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().equals("/api/merchant-applications/initial-password-setups"))
            return problem(ApplicationErrorCode.INVALID_SETUP_INPUT);
        if (request.getRequestURI().startsWith("/api/admin/merchant-applications"))
            return problem(ApplicationErrorCode.INVALID_REVIEW_INPUT);
        return problem(ApplicationErrorCode.INVALID_INPUT);
    }

    @ExceptionHandler({
        org.springframework.dao.DataAccessException.class,
        org.springframework.transaction.TransactionException.class
    })
    public ProblemDetail persistenceFailed() {
        return problem(ApplicationErrorCode.UNAVAILABLE);
    }

    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalidParameter() {
        return problem(ApplicationErrorCode.INVALID_INPUT);
    }

    private ProblemDetail problem(ApplicationErrorCode code) {
        var response = ProblemDetail.forStatusAndDetail(code.getStatus(), code.getMessage());
        response.setProperty("code", code.getCode());
        return response;
    }
}
