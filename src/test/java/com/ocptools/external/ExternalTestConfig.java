package com.ocptools.external;

import com.ocptools.config.ToolsConfig;

import java.time.Duration;
import java.util.Optional;

final class ExternalTestConfig {
    private ExternalTestConfig() {
    }

    static ToolsConfig withSecrets(String encryptionKey, String masterPassword) {
        return new ToolsConfig() {
            @Override public Soap soap() { return null; }
            @Override public Timeouts timeouts() { return null; }
            @Override public Cms cms() { return null; }
            @Override public TcpCheck tcpCheck() { return null; }
            @Override public Environments environments() { return null; }
            @Override public OcpMap ocpMap() { return null; }

            @Override
            public ExternalMonitor externalMonitor() {
                return new ExternalMonitor() {
                    @Override public boolean enabled() { return true; }
                    @Override public boolean tlsVerify() { return false; }
                    @Override public Duration interval() { return Duration.ofMinutes(10); }
                    @Override public Duration initialDelay() { return Duration.ZERO; }
                    @Override public int parallelism() { return 1; }
                    @Override public Duration sessionDuration() { return Duration.ofMinutes(15); }
                    @Override public int maxUnlockFailures() { return 3; }
                    @Override public Duration unlockWindow() { return Duration.ofMinutes(5); }
                    @Override public Duration unlockBlock() { return Duration.ofMinutes(15); }
                    @Override public Optional<String> encryptionKey() { return Optional.ofNullable(encryptionKey); }
                    @Override public Optional<String> kdbxMasterPassword() { return Optional.ofNullable(masterPassword); }
                };
            }
        };
    }
}
