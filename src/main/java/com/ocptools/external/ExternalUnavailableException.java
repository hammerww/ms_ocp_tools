package com.ocptools.external;

public class ExternalUnavailableException extends RuntimeException {
    public ExternalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
