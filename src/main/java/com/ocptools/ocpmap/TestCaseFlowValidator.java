package com.ocptools.ocpmap;

import com.ocptools.ocpmap.TestCaseFlowDefinition.Edge;
import com.ocptools.ocpmap.TestCaseFlowDefinition.Node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class TestCaseFlowValidator {
    private static final int MAX_NODES = 100;
    private static final int MAX_EDGES = 300;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> WAIT_MODES = Set.of("ANY", "ALL");
    private static final Set<String> LEGACY_TRIGGERS = Set.of("AUTO", "MANUAL", "RETURN");
    private static final Set<String> TRIGGERS = Set.of("CONTINUE", "QUERY");

    private TestCaseFlowValidator() {
    }

    public static TestCaseFlowDefinition validate(TestCaseFlowDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("La definición del flujo es obligatoria");
        }
        int version = definition.schemaVersion() == null ? 1 : definition.schemaVersion();
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("La versión del flujo no es compatible");
        }

        List<Node> inputNodes = definition.nodes() == null ? List.of() : definition.nodes();
        List<Edge> inputEdges = definition.edges() == null ? List.of() : definition.edges();
        if (inputNodes.isEmpty()) {
            throw new IllegalArgumentException("El flujo debe contener al menos un elemento");
        }
        if (inputNodes.size() > MAX_NODES || inputEdges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("El flujo excede el máximo de 100 elementos o 300 conexiones");
        }

        Set<String> nodeIds = new HashSet<>();
        List<Node> nodes = new ArrayList<>();
        for (Node node : inputNodes) {
            if (node == null || !validIdentifier(node.id())) {
                throw new IllegalArgumentException("Cada elemento del flujo debe tener un identificador válido");
            }
            String id = node.id().trim();
            if (!nodeIds.add(id)) {
                throw new IllegalArgumentException("Hay identificadores de elementos repetidos en el flujo");
            }
            String name = required(node.name(), "El nombre de cada elemento del flujo es obligatorio", 200);
            String detail = optional(node.detail(), 500, "El detalle de un elemento excede 500 caracteres");
            double x = coordinate(node.x());
            double y = coordinate(node.y());
            String waitMode = enumValue(node.waitMode(), "ANY", WAIT_MODES,
                    "El modo de espera del elemento debe ser ANY o ALL");
            if (node.catalogElementId() != null && node.catalogElementId() <= 0) {
                throw new IllegalArgumentException("El elemento de catálogo asociado no es válido");
            }
            nodes.add(new Node(id, node.catalogElementId(), name, detail, x, y, waitMode));
        }

        Set<String> edgeIds = new HashSet<>();
        Map<String, List<Edge>> edgesBySource = new TreeMap<>();
        for (Edge edge : inputEdges) {
            if (edge == null || !validIdentifier(edge.id())) {
                throw new IllegalArgumentException("Cada conexión debe tener un identificador válido");
            }
            String id = edge.id().trim();
            if (!edgeIds.add(id)) {
                throw new IllegalArgumentException("Hay identificadores de conexiones repetidos en el flujo");
            }
            String from = requiredIdentifier(edge.from(), "El origen de la conexión no es válido");
            String to = requiredIdentifier(edge.to(), "El destino de la conexión no es válido");
            if (!nodeIds.contains(from) || !nodeIds.contains(to)) {
                throw new IllegalArgumentException("Una conexión referencia un elemento inexistente");
            }
            if (from.equals(to)) {
                throw new IllegalArgumentException("Una conexión no puede regresar al mismo elemento");
            }
            String trigger = normalizeTrigger(edge.trigger(), version);
            int order = edge.order() == null ? 1 : edge.order();
            if (order < 1 || order > 1000) {
                throw new IllegalArgumentException("El orden de una conexión debe estar entre 1 y 1000");
            }
            String label = optional(edge.label(), 200, "La etiqueta de una conexión excede 200 caracteres");
            String responseLabel = "QUERY".equals(trigger)
                    ? optional(edge.responseLabel(), 200, "La etiqueta de respuesta excede 200 caracteres")
                    : null;
            edgesBySource.computeIfAbsent(from, ignored -> new ArrayList<>())
                    .add(new Edge(id, from, to, trigger, order, 0, label, responseLabel));
        }
        List<Edge> edges = new ArrayList<>();
        for (List<Edge> outgoing : edgesBySource.values()) {
            outgoing.sort((first, second) -> Integer.compare(first.order(), second.order()));
            for (int index = 0; index < outgoing.size(); index++) {
                Edge edge = outgoing.get(index);
                edges.add(new Edge(edge.id(), edge.from(), edge.to(), edge.trigger(), index + 1,
                        0, edge.label(), edge.responseLabel()));
            }
        }
        return new TestCaseFlowDefinition(2, List.copyOf(nodes), List.copyOf(edges));
    }

    private static String normalizeTrigger(String value, int schemaVersion) {
        String defaultValue = schemaVersion == 1 ? "AUTO" : "CONTINUE";
        String normalized = value == null || value.isBlank()
                ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
        if (schemaVersion == 1) {
            if (!LEGACY_TRIGGERS.contains(normalized)) {
                throw new IllegalArgumentException("El tipo de conexión legado debe ser AUTO, MANUAL o RETURN");
            }
            return "CONTINUE";
        }
        if (!TRIGGERS.contains(normalized)) {
            throw new IllegalArgumentException("El tipo de conexión debe ser CONTINUE o QUERY");
        }
        return normalized;
    }

    private static boolean validIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value.trim()).matches();
    }

    private static String requiredIdentifier(String value, String message) {
        if (!validIdentifier(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String required(String value, String message, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(message.replace("es obligatorio", "excede " + max + " caracteres"));
        }
        return normalized;
    }

    private static String optional(String value, int max, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static double coordinate(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 5000) {
            throw new IllegalArgumentException("Las coordenadas del flujo deben estar entre 0 y 5000");
        }
        return value;
    }

    private static String enumValue(String value, String defaultValue, Set<String> allowed, String message) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
