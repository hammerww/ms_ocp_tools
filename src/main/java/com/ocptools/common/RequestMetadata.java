package com.ocptools.common;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class RequestMetadata {
    private String correlationId;
    private String user;

    public String correlationId() {
        return correlationId;
    }

    public void correlationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String user() {
        return user;
    }

    public void user(String user) {
        this.user = user;
    }
}

