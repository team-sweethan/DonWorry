package com.donworry.investigation.controller;

import com.donworry.investigation.service.InvestigationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 공통 처리기 위치가 정해지기 전까지 모듈 controller 범위에서만 처리한다 (02-code 3절 보류)
@RestControllerAdvice(basePackages = "com.donworry.investigation.controller")
public class InvestigationExceptionHandler {

    @ExceptionHandler(InvestigationException.class)
    public ProblemDetail investigationFailed(InvestigationException exception) {
        var code = exception.getErrorCode();
        var problem = ProblemDetail.forStatusAndDetail(code.getStatus(), code.getMessage());
        problem.setProperty("code", code.getCode());
        return problem;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalidInput() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요.");
    }
}
