package com.ocptools.domain;

public record SoapRegisterResult(Outcome outcome, String technicalDetail) {

    public enum Outcome {
        SUCCESS,
        FAILED,
        TIMEOUT
    }

    public static SoapRegisterResult success() {
        return new SoapRegisterResult(Outcome.SUCCESS, null);
    }

    public static SoapRegisterResult failed(String detail) {
        return new SoapRegisterResult(Outcome.FAILED, detail);
    }

    public static SoapRegisterResult timeout(String detail) {
        return new SoapRegisterResult(Outcome.TIMEOUT, detail);
    }
}

