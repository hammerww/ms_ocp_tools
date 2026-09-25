package com.ocptools.ocpmap;

import com.ocptools.config.ToolsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class OcpNamespacePolicy {
    @Inject
    ToolsConfig config;

    public List<String> scanned() {
        return normalize(config.ocpMap().namespaces());
    }

    public List<String> visible() {
        List<String> scanned = scanned();
        List<String> requested = config.ocpMap().visibleNamespaces()
                .map(value -> normalize(Arrays.asList(value.split(","))))
                .orElseGet(List::of);
        if (requested.isEmpty()) {
            return scanned;
        }
        Set<String> allowed = new LinkedHashSet<>(scanned);
        return requested.stream().filter(allowed::contains).toList();
    }

    static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}
