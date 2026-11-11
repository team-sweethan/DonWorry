package com.donworry.fds.repository;

public interface OperatorAccessDailyCountProjection {
    java.time.LocalDate getDay();

    long getDeniedCount();
}
