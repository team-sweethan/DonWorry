package com.donworry.identity.controller;

import com.donworry.identity.security.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = MerchantApplicationController.class)
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
    public ProblemDetail invalidInput() {
        return problem(ApplicationErrorCode.INVALID_INPUT);
    }

    @ExceptionHandler({
        org.springframework.dao.DataAccessException.class,
        org.springframework.transaction.TransactionException.class
    })
    public ProblemDetail persistenceFailed() {
        return problem(ApplicationErrorCode.UNAVAILABLE);
    }

    private ProblemDetail problem(ApplicationErrorCode code) {
        var response = ProblemDetail.forStatusAndDetail(code.getStatus(), code.getMessage());
        response.setProperty("code", code.getCode());
        return response;
    }
}
