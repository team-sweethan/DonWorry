package com.donworry.fds.api;

public interface FdsAuthorizationResultRecorder {
    void record(Long evaluationId, AuthorizationResult result);

    enum AuthorizationResult {
        APPROVED,
        DECLINED
    }
}
