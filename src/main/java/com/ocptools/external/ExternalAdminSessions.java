package com.ocptools.external;

import com.ocptools.config.ToolsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ExternalAdminSessions {
    @Inject
    ToolsConfig config;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public ExternalModels.UnlockResponse unlock(String password, String clientKey) {
        Instant now = Instant.now();
        cleanup(now);
        Failures current = failures.get(clientKey);
        if (current != null && current.blockedUntil != null && current.blockedUntil.isAfter(now)) {
            throw new TooManyAttemptsException("Demasiados intentos. Intente nuevamente más tarde.");
        }
        String expected = config.externalMonitor().kdbxMasterPassword().orElse("");
        boolean valid = !expected.isBlank() && password != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            registerFailure(clientKey, now);
            throw new IllegalArgumentException("Clave maestra incorrecta");
        }
        failures.remove(clientKey);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(config.externalMonitor().sessionDuration());
        sessions.put(token, expiresAt);
        return new ExternalModels.UnlockResponse(token, expiresAt);
    }

    public void require(String token) {
        Instant expiresAt = token == null ? null : sessions.get(token);
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            if (token != null) sessions.remove(token);
            throw new SecurityException("Sesión administrativa ausente o vencida");
        }
    }

    private void registerFailure(String clientKey, Instant now) {
        failures.compute(clientKey, (ignored, previous) -> {
            if (previous == null || previous.windowStarted.plus(config.externalMonitor().unlockWindow()).isBefore(now)) {
                return new Failures(now, 1, null);
            }
            int count = previous.count + 1;
            Instant blockedUntil = count >= config.externalMonitor().maxUnlockFailures()
                    ? now.plus(config.externalMonitor().unlockBlock()) : null;
            return new Failures(previous.windowStarted, count, blockedUntil);
        });
    }

    private void cleanup(Instant now) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        failures.entrySet().removeIf(entry -> entry.getValue().blockedUntil != null
                && !entry.getValue().blockedUntil.isAfter(now));
    }

    private record Failures(Instant windowStarted, int count, Instant blockedUntil) {
    }

    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException(String message) {
            super(message);
        }
    }
}
