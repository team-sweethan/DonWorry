package com.donworry.fds.service;

// 로컬 시연에서도 직원이 오류 원인을 판단할 수 있는 P2 내부 오류만 선택한다.
public enum FdsDemoErrorScenario {
    TRANSIENT_FDS_EVALUATION_TIMEOUT("TRANSIENT_FDS_EVALUATION_TIMEOUT"),
    TRANSIENT_FDS_RULE_DATA_UNAVAILABLE("TRANSIENT_FDS_RULE_DATA_UNAVAILABLE"),
    FDS_EVALUATION_PROCESSING_FAILED("FDS_EVALUATION_PROCESSING_FAILED");

    private final String errorCode;

    FdsDemoErrorScenario(String errorCode) {
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
