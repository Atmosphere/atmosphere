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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link TaskStatusUpdateEvent} record, including JSON
 * serialization with {@code @JsonProperty("final")} on the {@code isFinal} field.
 */
class TaskStatusUpdateEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recordComponents() {
        var status = new Task.TaskStatus(TaskState.WORKING, "doing work");
        var event = new TaskStatusUpdateEvent("evt-1", status, false);

        assertEquals("evt-1", event.id());
        assertEquals(status, event.status());
        assertFalse(event.isFinal());
    }

    @Test
    void finalEvent() {
        var status = new Task.TaskStatus(TaskState.COMPLETED, "done");
        var event = new TaskStatusUpdateEvent("evt-2", status, true);

        assertTrue(event.isFinal());
    }

    @Test
    void jsonSerializesIsFinalAsFinaKey() throws Exception {
        var status = new Task.TaskStatus(TaskState.COMPLETED, "done");
        var event = new TaskStatusUpdateEvent("evt-1", status, true);

        var json = mapper.writeValueAsString(event);

        // The @JsonProperty("final") annotation maps isFinal -> "final" in JSON
        assertTrue(json.contains("\"final\""), "JSON should use 'final' key, got: " + json);
        assertFalse(json.contains("\"isFinal\""), "JSON should not use 'isFinal' key, got: " + json);
    }

    @Test
    void jsonDeserializationRoundTrip() throws Exception {
        var status = new Task.TaskStatus(TaskState.WORKING, "processing");
        var event = new TaskStatusUpdateEvent("evt-3", status, false);

        var json = mapper.writeValueAsString(event);
        var deserialized = mapper.readValue(json, TaskStatusUpdateEvent.class);

        assertEquals(event.id(), deserialized.id());
        assertEquals(event.status().state(), deserialized.status().state());
        assertEquals(event.status().message(), deserialized.status().message());
        assertEquals(event.isFinal(), deserialized.isFinal());
    }

    @Test
    void jsonRoundTripWithFinalTrue() throws Exception {
        var status = new Task.TaskStatus(TaskState.FAILED, "error occurred");
        var event = new TaskStatusUpdateEvent("evt-4", status, true);

        var json = mapper.writeValueAsString(event);
        var deserialized = mapper.readValue(json, TaskStatusUpdateEvent.class);

        assertTrue(deserialized.isFinal());
        assertEquals(TaskState.FAILED, deserialized.status().state());
    }

    @Test
    void jsonOmitsNullStatusMessage() throws Exception {
        var status = new Task.TaskStatus(TaskState.CANCELED, null);
        var event = new TaskStatusUpdateEvent("evt-5", status, true);

        var json = mapper.writeValueAsString(event);

        // @JsonInclude(NON_NULL) should omit null message
        assertFalse(json.contains("\"message\""), "Null message should be omitted: " + json);
    }

    @Test
    void recordEquality() {
        var status = new Task.TaskStatus(TaskState.WORKING, "active");
        var event1 = new TaskStatusUpdateEvent("evt-1", status, false);
        var event2 = new TaskStatusUpdateEvent("evt-1", status, false);
        var event3 = new TaskStatusUpdateEvent("evt-2", status, false);

        assertEquals(event1, event2);
        assertNotEquals(event1, event3);
    }

    @Test
    void recordHashCode() {
        var status = new Task.TaskStatus(TaskState.WORKING, "active");
        var event1 = new TaskStatusUpdateEvent("evt-1", status, false);
        var event2 = new TaskStatusUpdateEvent("evt-1", status, false);

        assertEquals(event1.hashCode(), event2.hashCode());
    }
}
