package com.ocptools.ocpmap;

import java.util.List;

public record TestCaseFlowDefinition(
        Integer schemaVersion,
        List<Node> nodes,
        List<Edge> edges
) {
    public record Node(
            String id,
            Long catalogElementId,
            String name,
            String detail,
            Double x,
            Double y,
            String waitMode
    ) {
        public Node(String id, Long catalogElementId, String name, Double x, Double y, String waitMode) {
            this(id, catalogElementId, name, null, x, y, waitMode);
        }
    }

    public record Edge(
            String id,
            String from,
            String to,
            String trigger,
            Integer order,
            Integer repetitions,
            String label,
            String responseLabel
    ) {
        public Edge(String id, String from, String to, String trigger, Integer order,
                    Integer repetitions, String label) {
            this(id, from, to, trigger, order, repetitions, label, null);
        }
    }
}
