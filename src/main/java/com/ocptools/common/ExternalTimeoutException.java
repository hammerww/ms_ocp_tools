package com.ocptools.common;

public class ExternalTimeoutException extends RuntimeException {
    public ExternalTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

