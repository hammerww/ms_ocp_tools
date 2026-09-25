package com.ocptools.soap;

public class SoapProtocolException extends Exception {
    public SoapProtocolException(String message) {
        super(message);
    }

    public SoapProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

