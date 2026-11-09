package com.donworry.fds.repository;

public interface OperatorAccessDetectionPathCountProjection {
    Long getDetectionId();

    String getHttpMethod();

    String getPath();

    long getDeniedCount();
}
