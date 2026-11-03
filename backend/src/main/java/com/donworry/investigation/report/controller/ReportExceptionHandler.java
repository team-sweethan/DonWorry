package com.donworry.investigation.report.controller;

import com.donworry.investigation.service.InvestigationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

// P3 InvestigationExceptionHandler는 investigation.controller 범위라 P4 컨트롤러에는 닿지 않는다 — 같은 응답 형식으로 둔다
// 공통 처리기 위치가 정해지면 합친다 (02-code 3절 보류)
@RestControllerAdvice(basePackages = "com.donworry.investigation.report.controller")
public class ReportExceptionHandler {

    @ExceptionHandler(InvestigationException.class)
    public ProblemDetail investigationFailed(InvestigationException exception) {
        var code = exception.getErrorCode();
        var problem = ProblemDetail.forStatusAndDetail(code.getStatus(), code.getMessage());
        problem.setProperty("code", code.getCode());
        return problem;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalidInput() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요.");
    }

    // 보고서 ID 불일치(400)를 다른 오류와 같은 형식으로 바로 돌려준다 (서비스 키 401은 보안 체인이 답한다)
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail rejected(ResponseStatusException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
    }
}
