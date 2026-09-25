package com.ocptools.config;

import io.smallrye.config.ConfigMapping;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "ocp-tools")
public interface ToolsConfig {

    Soap soap();

    Timeouts timeouts();

    Cms cms();

    TcpCheck tcpCheck();

    Environments environments();

    OcpMap ocpMap();

    ExternalMonitor externalMonitor();

    interface Soap {
        URI queryUrl();

        URI registerUrl();
    }

    interface Timeouts {
        int cmsQuerySeconds();

        Duration soapQuery();

        Duration soapRegister();
    }

    interface Cms {
        Duration postRegisterUpdateDelay();
    }

    interface TcpCheck {
        Duration timeout();
    }

    interface Environments {
        String pmx1();

        String pmx3();

        String pmx4();
    }

    interface OcpMap {
        List<String> namespaces();

        Optional<String> visibleNamespaces();

        URI consoleBaseUrl();

        Duration databaseRetry();

        Duration scanInterval();

        Duration scanInitialDelay();
    }

    interface ExternalMonitor {
        boolean enabled();

        boolean tlsVerify();

        Duration interval();

        Duration initialDelay();

        int parallelism();

        Duration sessionDuration();

        int maxUnlockFailures();

        Duration unlockWindow();

        Duration unlockBlock();

        Optional<String> encryptionKey();

        Optional<String> kdbxMasterPassword();
    }
}
