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
package org.atmosphere.a2a.types;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke tests for v1.0.0 {@link Task}, {@link TaskStatus}, and the two event records. */
class TaskAndEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void taskUsesHistoryNotMessages() throws Exception {
        var t = new Task("t1", "ctx1", TaskStatus.of(TaskState.WORKING),
                List.of(), List.of(Message.user("hi")), null);
        var json = mapper.writeValueAsString(t);
        assertTrue(json.contains("\"history\""), "v1.0.0 renamed messages → history; got " + json);
        assertFalse(json.contains("\"messages\":"), "messages field is gone in v1.0.0");
    }

    @Test
    void taskStatusCarriesTimestamp() {
        var s = TaskStatus.of(TaskState.WORKING);
        assertNotNull(s.timestamp());
    }

    @Test
    void taskStatusMessageIsAMessageRecord() {
        var s = TaskStatus.of(TaskState.WORKING, "processing");
        assertNotNull(s.message());
        assertEquals("processing", s.message().parts().getFirst().text());
    }

    @Test
    void taskStatusUpdateEventCarriesContextId() {
        var event = new TaskStatusUpdateEvent("t1", "ctx1",
                TaskStatus.of(TaskState.COMPLETED, "done"));
        assertEquals("t1", event.taskId());
        assertEquals("ctx1", event.contextId());
        assertNull(event.metadata());
    }

    @Test
    void taskStatusUpdateEventNoFinalField() throws Exception {
        var event = new TaskStatusUpdateEvent("t1", "ctx1",
                TaskStatus.of(TaskState.COMPLETED));
        var json = mapper.writeValueAsString(event);
        assertFalse(json.contains("\"final\""),
                "v1.0.0 dropped the redundant `final` field; got " + json);
    }

    @Test
    void taskArtifactUpdateEventCarriesAppendAndLastChunk() {
        var artifact = Artifact.text("chunk");
        var event = new TaskArtifactUpdateEvent("t1", "ctx1", artifact, true, false, null);
        assertEquals(true, event.append());
        assertEquals(false, event.lastChunk());
    }
}
