package com.ocptools.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocptools.config.ToolsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class CredentialCipher {
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    @Inject
    ToolsConfig config;

    @Inject
    ObjectMapper objectMapper;

    private final SecureRandom random = new SecureRandom();

    public Encrypted encrypt(ExternalModels.CredentialSecret secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new Encrypted(cipher.doFinal(objectMapper.writeValueAsBytes(secret)), iv);
        } catch (Exception exception) {
            throw new IllegalStateException("No fue posible cifrar la credencial", exception);
        }
    }

    public ExternalModels.CredentialSecret decrypt(byte[] payload, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return objectMapper.readValue(cipher.doFinal(payload), ExternalModels.CredentialSecret.class);
        } catch (Exception exception) {
            throw new IllegalStateException("No fue posible descifrar la credencial", exception);
        }
    }

    public boolean isConfigured() {
        try {
            key();
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private SecretKeySpec key() {
        String encoded = config.externalMonitor().encryptionKey().orElse("").trim();
        if (encoded.isEmpty()) {
            throw new IllegalStateException("Falta EXTERNAL_CREDENTIAL_ENCRYPTION_KEY");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("EXTERNAL_CREDENTIAL_ENCRYPTION_KEY no es Base64 válido", exception);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("EXTERNAL_CREDENTIAL_ENCRYPTION_KEY debe contener 32 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public record Encrypted(byte[] payload, byte[] iv) {
    }
}
