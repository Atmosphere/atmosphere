/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.admin.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * JSON-serializable description of an executable workflow authored through
 * the admin control plane. A workflow is a directed graph of {@link Node}s
 * connected by {@link Edge}s; nodes name the work to do (an agent, a
 * conditional branch, a fan-out, an approval gate); edges describe how the
 * output of one node reaches the input of the next.
 *
 * <p>This is the on-disk format the authoring UI saves and the runtime
 * (planned in a follow-up commit on this branch) executes by dispatching
 * each node through {@code @Coordinator} / {@code AgentFleet}.</p>
 *
 * <p>Identifier safety: workflow / node / edge identifiers and node types
 * are validated against {@code [A-Za-z0-9_-]+} at construction time so
 * a manifest cannot leak path-injection characters into URLs, log lines,
 * or downstream dispatch keys (Correctness Invariant #4 — Boundary
 * Safety).</p>
 */
public record WorkflowManifest(
        String id,
        String name,
        String description,
        List<Node> nodes,
        List<Edge> edges,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        int version
) {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_\\-]+$");

    public WorkflowManifest {
        id = requireSafeId(id, "id");
        name = requireNonBlank(name, "name");
        description = description != null ? description : "";
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
        edges = edges != null ? List.copyOf(edges) : List.of();
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        validateEdgeReferences(nodes, edges);
    }

    /** Node in the workflow graph. */
    public record Node(
            String id,
            String type,
            String label,
            Map<String, Object> config
    ) {
        public Node {
            id = requireSafeId(id, "node id");
            type = requireSafeId(type, "node type");
            label = label != null ? label : id;
            config = config != null ? Map.copyOf(config) : Map.of();
        }
    }

    /** Edge connecting two nodes. {@code condition} is optional. */
    public record Edge(
            String from,
            String to,
            String condition
    ) {
        public Edge {
            from = requireSafeId(from, "edge from");
            to = requireSafeId(to, "edge to");
            if (from.equals(to)) {
                throw new IllegalArgumentException("edge cannot loop on itself: " + from);
            }
        }
    }

    /** Built-in node types. Custom node types are permitted but unrecognized
     * types skip executor-side dispatch and emit a warning instead.
     */
    public static final class NodeType {
        public static final String AGENT = "agent";
        public static final String CONDITION = "condition";
        public static final String FAN_OUT = "fan-out";
        public static final String JOIN = "join";
        public static final String APPROVAL = "approval";
        public static final String OUTPUT = "output";

        private NodeType() {
        }
    }

    private static void validateEdgeReferences(List<Node> nodes, List<Edge> edges) {
        if (edges.isEmpty()) {
            return;
        }
        var nodeIds = new java.util.HashSet<String>();
        for (var n : nodes) {
            nodeIds.add(n.id());
        }
        for (var e : edges) {
            if (!nodeIds.contains(e.from())) {
                throw new IllegalArgumentException(
                        "edge.from references unknown node: " + e.from());
            }
            if (!nodeIds.contains(e.to())) {
                throw new IllegalArgumentException(
                        "edge.to references unknown node: " + e.to());
            }
        }
    }

    private static String requireSafeId(String value, String label) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must match [A-Za-z0-9_-]+ (was: " + value + ")");
        }
        return value;
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    /** Convenience: build a new manifest with bumped version and now() timestamp. */
    public WorkflowManifest withRevision(List<Node> newNodes, List<Edge> newEdges) {
        return new WorkflowManifest(
                this.id,
                this.name,
                this.description,
                newNodes,
                newEdges,
                this.createdBy,
                this.createdAt,
                Instant.now(),
                this.version + 1);
    }

    /** Convenience: build a brand-new manifest with timestamps set to now and version 1. */
    public static WorkflowManifest create(
            String id,
            String name,
            String description,
            List<Node> nodes,
            List<Edge> edges,
            String createdBy) {
        Instant now = Instant.now();
        return new WorkflowManifest(
                id, name, description, nodes, edges, createdBy, now, now, 1);
    }
}
