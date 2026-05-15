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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowManifestTest {

    @Test
    void createBuildsValidManifest() {
        var manifest = WorkflowManifest.create(
                "support-bot",
                "Support Bot",
                "RAG over docs + escalation",
                List.of(
                        new WorkflowManifest.Node("intake", WorkflowManifest.NodeType.AGENT,
                                "Intake", Map.of("agent", "intake-agent")),
                        new WorkflowManifest.Node("escalate", WorkflowManifest.NodeType.APPROVAL,
                                "Manual review", Map.of("reviewer", "support-leads"))),
                List.of(new WorkflowManifest.Edge("intake", "escalate", null)),
                "jfarcand");

        assertEquals(1, manifest.version());
        assertEquals(2, manifest.nodes().size());
        assertEquals(1, manifest.edges().size());
        assertTrue(manifest.createdAt().equals(manifest.updatedAt()),
                "createdAt and updatedAt match on first save");
    }

    @Test
    void withRevisionBumpsVersionAndUpdatedAt() throws InterruptedException {
        var first = WorkflowManifest.create(
                "x", "X", null,
                List.of(new WorkflowManifest.Node("a", "agent", "A", Map.of())),
                List.of(),
                "user");
        Thread.sleep(2); // ensure now() differs from createdAt
        var second = first.withRevision(
                List.of(
                        new WorkflowManifest.Node("a", "agent", "A", Map.of()),
                        new WorkflowManifest.Node("b", "output", "B", Map.of())),
                List.of(new WorkflowManifest.Edge("a", "b", null)));

        assertEquals(2, second.version());
        assertEquals(first.createdAt(), second.createdAt());
        assertTrue(second.updatedAt().isAfter(first.updatedAt()));
    }

    @Test
    void invalidIdentifiersRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowManifest.Node("has space", "agent", "X", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowManifest.Node("ok", "bad/type", "X", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowManifest.Edge("a", "a", null),
                "self-loop edges must be rejected");
    }

    @Test
    void edgeReferencesValidated() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowManifest.create(
                "wf", "WF", null,
                List.of(new WorkflowManifest.Node("a", "agent", "A", Map.of())),
                List.of(new WorkflowManifest.Edge("a", "nonexistent", null)),
                "u"));
    }

    @Test
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowManifest.create(
                "x", "  ", null, List.of(), List.of(), "u"));
    }

    @Test
    void versionMustBePositive() {
        var now = java.time.Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new WorkflowManifest(
                "x", "X", "", List.of(), List.of(), "u", now, now, 0));
    }
}
