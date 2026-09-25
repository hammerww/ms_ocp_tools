package com.ocptools.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialCipherTest {
    @Test
    void encryptsAndDecryptsWithoutLeavingPlainText() {
        CredentialCipher cipher = cipher(key((byte) 7));
        ExternalModels.CredentialSecret secret = new ExternalModels.CredentialSecret(
                "monitor-user", "very-secret", null, null, null, Map.of("url", "https://auth.example"));

        CredentialCipher.Encrypted encrypted = cipher.encrypt(secret);

        assertFalse(new String(encrypted.payload()).contains("very-secret"));
        assertEquals(secret, cipher.decrypt(encrypted.payload(), encrypted.iv()));
    }

    @Test
    void rejectsDecryptionWithAnotherKey() {
        CredentialCipher.Encrypted encrypted = cipher(key((byte) 1)).encrypt(
                new ExternalModels.CredentialSecret("user", "secret", null, null, null, Map.of()));

        assertThrows(IllegalStateException.class,
                () -> cipher(key((byte) 2)).decrypt(encrypted.payload(), encrypted.iv()));
    }

    private static CredentialCipher cipher(String key) {
        CredentialCipher cipher = new CredentialCipher();
        cipher.config = ExternalTestConfig.withSecrets(key, "master");
        cipher.objectMapper = new ObjectMapper();
        return cipher;
    }

    private static String key(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
