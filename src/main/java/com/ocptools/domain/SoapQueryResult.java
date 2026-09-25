package com.ocptools.domain;

public record SoapQueryResult(Outcome outcome, String subsystem, String technicalDetail) {

    public enum Outcome {
        FOUND,
        NOT_FOUND,
        ERROR,
        TIMEOUT
    }

    public static SoapQueryResult found(String subsystem) {
        return new SoapQueryResult(Outcome.FOUND, subsystem, null);
    }

    public static SoapQueryResult notFound() {
        return new SoapQueryResult(Outcome.NOT_FOUND, null, null);
    }

    public static SoapQueryResult error(String detail) {
        return new SoapQueryResult(Outcome.ERROR, null, detail);
    }

    public static SoapQueryResult timeout(String detail) {
        return new SoapQueryResult(Outcome.TIMEOUT, null, detail);
    }
}

