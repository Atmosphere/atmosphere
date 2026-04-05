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
package org.atmosphere.checkpoint;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowSnapshotTest {

    @Test
    void rootSnapshotHasNoParent() {
        var snapshot = WorkflowSnapshot.root("coord-1", "state");
        assertTrue(snapshot.isRoot());
        assertTrue(snapshot.parent().isEmpty());
        assertEquals("coord-1", snapshot.coordinationId());
        assertEquals("state", snapshot.state());
    }

    @Test
    void deriveWithLinksToParentAndKeepsCoordinationId() {
        var root = WorkflowSnapshot.root("coord-1", "s0");
        var child = root.deriveWith("s1");

        assertFalse(child.isRoot());
        assertEquals(root.id(), child.parent().orElseThrow());
        assertEquals("coord-1", child.coordinationId());
        assertEquals("s1", child.state());
        assertNotEquals(root.id(), child.id());
    }

    @Test
    void builderDefaultsFillInIdAndCreatedAt() {
        var snapshot = WorkflowSnapshot.<String>builder()
                .coordinationId("c")
                .state("x")
                .build();
        assertNotNull(snapshot.id());
        assertNotNull(snapshot.createdAt());
    }

    @Test
    void metadataIsImmutableCopy() {
        var original = new java.util.HashMap<String, String>();
        original.put("k", "v");
        var snapshot = WorkflowSnapshot.<String>builder()
                .coordinationId("c")
                .state("x")
                .metadata(original)
                .build();
        original.put("k2", "v2");
        assertEquals(1, snapshot.metadata().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.metadata().put("new", "val"));
    }

    @Test
    void nullMetadataIsTreatedAsEmpty() {
        var snapshot = WorkflowSnapshot.<String>builder()
                .coordinationId("c")
                .state("x")
                .metadata(null)
                .build();
        assertEquals(Map.of(), snapshot.metadata());
    }

    @Test
    void coordinationIdIsRequired() {
        assertThrows(NullPointerException.class,
                () -> WorkflowSnapshot.<String>builder().state("x").build());
    }
}
