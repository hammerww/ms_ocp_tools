package com.ocptools.external;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalAdminSessionsTest {
    @Test
    void createsAnOpaqueSessionForTheMasterPassword() {
        ExternalAdminSessions sessions = sessions();

        ExternalModels.UnlockResponse response = sessions.unlock("master-password", "client-a");

        assertTrue(response.token().length() >= 40);
        assertDoesNotThrow(() -> sessions.require(response.token()));
        assertThrows(SecurityException.class, () -> sessions.require("not-a-token"));
    }

    @Test
    void blocksTheClientAfterRepeatedFailures() {
        ExternalAdminSessions sessions = sessions();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(IllegalArgumentException.class,
                    () -> sessions.unlock("wrong", "client-b"));
        }

        assertThrows(ExternalAdminSessions.TooManyAttemptsException.class,
                () -> sessions.unlock("master-password", "client-b"));
    }

    private static ExternalAdminSessions sessions() {
        ExternalAdminSessions sessions = new ExternalAdminSessions();
        sessions.config = ExternalTestConfig.withSecrets(
                Base64.getEncoder().encodeToString(new byte[32]), "master-password");
        return sessions;
    }
}
