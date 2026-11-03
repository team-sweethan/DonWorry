package com.donworry.fds.api;

// 같은 요청 ID가 다른 결제 입력에 재사용됐음을 P5가 구분하도록 공개한다.
public class FdsEvaluationRequestConflictException extends RuntimeException {

    public FdsEvaluationRequestConflictException() {
        super("evaluationRequestId is already used by a different evaluation request");
    }
}
