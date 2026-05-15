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

import org.atmosphere.admin.ControlAuditLog;
import org.atmosphere.admin.ControlAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkflowControllerTest {

    private InMemoryWorkflowStore store;
    private ControlAuditLog auditLog;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        auditLog = new ControlAuditLog(50);
    }

    @Test
    void saveRequiresAuthorization() {
        var controller = new WorkflowController(store, ControlAuthorizer.DENY_ALL, auditLog);

        var manifest = WorkflowManifest.create(
                "wf1", "Workflow 1", null,
                List.of(new WorkflowManifest.Node("a", "agent", "A", Map.of())),
                List.of(), "alice");

        assertThrows(SecurityException.class,
                () -> controller.save(manifest, "alice"),
                "default-deny authorizer must reject saves");
        assertTrue(store.list().isEmpty(), "no manifest should be stored on denial");
    }

    @Test
    void saveAuditsSuccessfulWrite() {
        var controller = new WorkflowController(store, ControlAuthorizer.ALLOW_ALL, auditLog);

        var manifest = WorkflowManifest.create(
                "wf1", "Workflow 1", null,
                List.of(new WorkflowManifest.Node("a", "agent", "A", Map.of())),
                List.of(), "alice");

        var saved = controller.save(manifest, "alice");
        assertEquals(1, saved.version());
        assertEquals(1, store.list().size());

        var entries = auditLog.entries();
        assertEquals(1, entries.size());
        assertEquals("workflow.write", entries.get(0).action());
        assertEquals("alice", entries.get(0).principal());
        assertEquals("wf1", entries.get(0).target());
    }

    @Test
    void deleteRequiresAuthorization() {
        var controller = new WorkflowController(store, ControlAuthorizer.DENY_ALL, auditLog);
        store.save(WorkflowManifest.create("wf1", "Workflow 1", null,
                List.of(new WorkflowManifest.Node("a", "agent", "A", Map.of())),
                List.of(), "alice"));

        assertThrows(SecurityException.class,
                () -> controller.delete("wf1", "alice"));
        assertFalse(store.findById("wf1").isEmpty(),
                "denied delete must leave the manifest in place");
    }

    @Test
    void deleteIsIdempotent() {
        var controller = new WorkflowController(store, ControlAuthorizer.ALLOW_ALL, auditLog);
        controller.delete("does-not-exist", "alice");
        controller.delete("does-not-exist", "alice");
        // Audit log still recorded both attempts for accountability
        assertEquals(2, auditLog.entries().size());
    }

    @Test
    void requirePrincipalAuthorizerRejectsAnonymous() {
        var controller = new WorkflowController(
                store, ControlAuthorizer.REQUIRE_PRINCIPAL, auditLog);

        var manifest = WorkflowManifest.create(
                "wf1", "Workflow 1", null,
                List.of(new WorkflowManifest.Node("a", "agent", "A", Map.of())),
                List.of(), null);

        assertThrows(SecurityException.class,
                () -> controller.save(manifest, null),
                "anonymous principal must be rejected under REQUIRE_PRINCIPAL");
        assertThrows(SecurityException.class,
                () -> controller.save(manifest, "  "),
                "blank principal must be rejected under REQUIRE_PRINCIPAL");
    }

    @Test
    void listAndGetAreReadOnly() {
        var controller = new WorkflowController(store, ControlAuthorizer.DENY_ALL, auditLog);
        store.save(WorkflowManifest.create("wf1", "Workflow 1", null,
                List.of(new WorkflowManifest.Node("a", "agent", "A", Map.of())),
                List.of(), "alice"));

        assertEquals(1, controller.list().size(),
                "list() must not gate on the write-only authorizer");
        assertTrue(controller.get("wf1").isPresent(),
                "get() must not gate on the write-only authorizer");
        assertTrue(controller.get("nonexistent").isEmpty());
    }

    @Test
    void inMemoryStoreRejectsVersionConflict() {
        var first = WorkflowManifest.create("wf1", "Workflow 1", null,
                List.of(new WorkflowManifest.Node("a", "agent", "A", Map.of())),
                List.of(), "alice");
        store.save(first);

        // Caller skips a version — server should refuse
        var stale = first.withRevision(first.nodes(), first.edges())
                .withRevision(first.nodes(), first.edges());

        var ex = assertThrows(WorkflowStoreException.class, () -> store.save(stale));
        assertEquals(WorkflowStoreException.Kind.VERSION_CONFLICT, ex.kind());
    }
}
