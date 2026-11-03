package com.donworry.identity.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 401·403 핸들러가 같은 ProblemDetail JSON 형식으로 응답하도록 공통 작성 처리를 맡는다.
@Component
@RequiredArgsConstructor
class SecurityProblemResponseWriter {

    private final JsonMapper jsonMapper;

    void write(HttpServletResponse response, AuthErrorCode errorCode) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), errorCode.getMessage());
        problem.setProperty("code", errorCode.getCode());

        response.setStatus(errorCode.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
