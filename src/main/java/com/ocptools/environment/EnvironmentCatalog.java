package com.ocptools.environment;

import com.ocptools.config.ToolsConfig;
import com.ocptools.domain.EnvironmentTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class EnvironmentCatalog {
    private final Map<String, EnvironmentTarget> byLabel;
    private final Map<String, EnvironmentTarget> bySubsystem;

    @Inject
    public EnvironmentCatalog(ToolsConfig config) {
        Map<String, EnvironmentTarget> labels = new LinkedHashMap<>();
        add(labels, "PMX3", config.environments().pmx3());
        add(labels, "PMX1", config.environments().pmx1());
        add(labels, "PMX4", config.environments().pmx4());
        byLabel = Map.copyOf(labels);

        Map<String, EnvironmentTarget> subsystems = new LinkedHashMap<>();
        labels.values().forEach(target -> subsystems.put(normalize(target.subsystem()), target));
        bySubsystem = Map.copyOf(subsystems);
    }

    public List<EnvironmentTarget> all() {
        return List.of(byLabel.get("PMX3"), byLabel.get("PMX1"), byLabel.get("PMX4"));
    }

    public Optional<EnvironmentTarget> byLabel(String label) {
        return Optional.ofNullable(byLabel.get(normalize(label)));
    }

    public Optional<EnvironmentTarget> bySubsystem(String subsystem) {
        return Optional.ofNullable(bySubsystem.get(normalize(subsystem)));
    }

    public String displayNameForSubsystem(String subsystem) {
        return bySubsystem(subsystem)
                .map(EnvironmentTarget::label)
                .orElse("subsistema " + safeUnknown(subsystem));
    }

    private static void add(Map<String, EnvironmentTarget> target, String label, String subsystem) {
        if (subsystem == null || subsystem.isBlank()) {
            throw new IllegalStateException("El subsistema de " + label + " no puede estar vacío");
        }
        target.put(label, new EnvironmentTarget(label, subsystem.trim()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeUnknown(String value) {
        if (value == null || value.isBlank()) {
            return "desconocido";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "");
    }
}

