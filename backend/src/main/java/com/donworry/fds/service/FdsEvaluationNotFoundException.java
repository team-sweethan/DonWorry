package com.donworry.fds.service;

public class FdsEvaluationNotFoundException extends RuntimeException {

    public FdsEvaluationNotFoundException() {
        super("FDS evaluation was not found");
    }
}
