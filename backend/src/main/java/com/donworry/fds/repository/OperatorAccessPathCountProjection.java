package com.donworry.fds.repository;

public interface OperatorAccessPathCountProjection {
    String getHttpMethod();

    String getPath();

    long getDeniedCount();
}
