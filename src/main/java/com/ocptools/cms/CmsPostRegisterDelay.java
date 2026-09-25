package com.ocptools.cms;

import com.ocptools.config.ToolsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

@ApplicationScoped
public class CmsPostRegisterDelay {

    @Inject
    ToolsConfig config;

    /**
     * Espera el intervalo configurado sin ocultar una interrupción del hilo.
     *
     * @return {@code true} cuando se completó la espera; {@code false} si fue interrumpida.
     */
    public boolean await() {
        Duration delay = config.cms().postRegisterUpdateDelay();
        if (delay == null || delay.isZero()) {
            return true;
        }
        if (delay.isNegative()) {
            throw new IllegalStateException("CMS_POST_REGISTER_UPDATE_DELAY no puede ser negativo");
        }

        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
