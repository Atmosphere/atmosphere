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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the {@link TaskState} enum values and JSON serialization.
 */
class TaskStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(7, TaskState.values().length);
    }

    @Test
    void workingState() {
        assertEquals("WORKING", TaskState.WORKING.name());
        assertEquals(0, TaskState.WORKING.ordinal());
    }

    @Test
    void completedState() {
        assertEquals("COMPLETED", TaskState.COMPLETED.name());
        assertEquals(1, TaskState.COMPLETED.ordinal());
    }

    @Test
    void failedState() {
        assertEquals("FAILED", TaskState.FAILED.name());
        assertEquals(2, TaskState.FAILED.ordinal());
    }

    @Test
    void canceledState() {
        assertEquals("CANCELED", TaskState.CANCELED.name());
        assertEquals(3, TaskState.CANCELED.ordinal());
    }

    @Test
    void rejectedState() {
        assertEquals("REJECTED", TaskState.REJECTED.name());
        assertEquals(4, TaskState.REJECTED.ordinal());
    }

    @Test
    void inputRequiredState() {
        assertEquals("INPUT_REQUIRED", TaskState.INPUT_REQUIRED.name());
        assertEquals(5, TaskState.INPUT_REQUIRED.ordinal());
    }

    @Test
    void authRequiredState() {
        assertEquals("AUTH_REQUIRED", TaskState.AUTH_REQUIRED.name());
        assertEquals(6, TaskState.AUTH_REQUIRED.ordinal());
    }

    @Test
    void valueOfRoundTrips() {
        for (var state : TaskState.values()) {
            assertEquals(state, TaskState.valueOf(state.name()));
        }
    }

    @Test
    void valueOfInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> TaskState.valueOf("UNKNOWN"));
    }

    @Test
    void jsonSerializationRoundTrip() throws Exception {
        for (var state : TaskState.values()) {
            var json = mapper.writeValueAsString(state);
            assertNotNull(json);
            var deserialized = mapper.readValue(json, TaskState.class);
            assertEquals(state, deserialized);
        }
    }

    @Test
    void jsonSerializesAsString() throws Exception {
        var json = mapper.writeValueAsString(TaskState.WORKING);
        assertEquals("\"WORKING\"", json);
    }
}
