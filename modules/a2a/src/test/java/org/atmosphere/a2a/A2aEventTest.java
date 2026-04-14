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
package org.atmosphere.a2a;

import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.Part;
import org.atmosphere.a2a.types.Task;
import org.atmosphere.a2a.types.TaskArtifactUpdateEvent;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.a2a.types.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aEventTest {

    @Test
    void taskStatusUpdateEventFields() {
        var event = new TaskStatusUpdateEvent("t1",
                new Task.TaskStatus(TaskState.COMPLETED, "done"), true);
        assertEquals("t1", event.id());
        assertEquals(TaskState.COMPLETED, event.status().state());
        assertEquals("done", event.status().message());
        assertTrue(event.isFinal());
    }

    @Test
    void taskStatusUpdateEventNonFinal() {
        var event = new TaskStatusUpdateEvent("t2",
                new Task.TaskStatus(TaskState.WORKING, null), false);
        assertFalse(event.isFinal());
        assertNull(event.status().message());
    }

    @Test
    void taskArtifactUpdateEventFields() {
        var artifact = Artifact.named("result", "desc",
                List.of(new Part.TextPart("output")));
        var event = new TaskArtifactUpdateEvent("t1", artifact);
        assertEquals("t1", event.id());
        assertNotNull(event.artifact().artifactId());
        assertEquals("result", event.artifact().name());
    }

    @Test
    void taskArtifactUpdateEventWithNullArtifact() {
        var event = new TaskArtifactUpdateEvent("t1", null);
        assertEquals("t1", event.id());
        assertNull(event.artifact());
    }
}
