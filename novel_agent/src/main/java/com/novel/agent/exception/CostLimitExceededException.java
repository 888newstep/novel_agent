package com.novel.agent.exception;

public class CostLimitExceededException extends RuntimeException {

    public CostLimitExceededException(String message) {
        super(message);
    }
}
