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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies v1.0.0 {@link TaskState} wire-form encoding and lifecycle helpers. */
class TaskStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void v1AddedSubmittedState() {
        assertEquals("TASK_STATE_SUBMITTED", TaskState.SUBMITTED.wire());
    }

    @Test
    void wireFormFollowsProtoJson() {
        assertEquals("TASK_STATE_WORKING", TaskState.WORKING.wire());
        assertEquals("TASK_STATE_COMPLETED", TaskState.COMPLETED.wire());
        assertEquals("TASK_STATE_AUTH_REQUIRED", TaskState.AUTH_REQUIRED.wire());
    }

    @Test
    void serializationUsesProtoJsonName() throws Exception {
        var json = mapper.writeValueAsString(TaskState.WORKING);
        assertEquals("\"TASK_STATE_WORKING\"", json);
    }

    @Test
    void deserializationAcceptsProtoJsonName() throws Exception {
        assertEquals(TaskState.WORKING,
                mapper.readValue("\"TASK_STATE_WORKING\"", TaskState.class));
    }

    @Test
    void deserializationAcceptsLegacyShortName() throws Exception {
        assertEquals(TaskState.WORKING, mapper.readValue("\"WORKING\"", TaskState.class));
        assertEquals(TaskState.WORKING, mapper.readValue("\"working\"", TaskState.class));
    }

    @Test
    void unknownStateRejected() {
        assertThrows(Exception.class,
                () -> mapper.readValue("\"TASK_STATE_NONESUCH\"", TaskState.class));
    }

    @Test
    void terminalStates() {
        assertTrue(TaskState.COMPLETED.isTerminal());
        assertTrue(TaskState.FAILED.isTerminal());
        assertTrue(TaskState.CANCELED.isTerminal());
        assertTrue(TaskState.REJECTED.isTerminal());
        assertFalse(TaskState.WORKING.isTerminal());
        assertFalse(TaskState.SUBMITTED.isTerminal());
    }

    @Test
    void interruptedStates() {
        assertTrue(TaskState.INPUT_REQUIRED.isInterrupted());
        assertTrue(TaskState.AUTH_REQUIRED.isInterrupted());
        assertFalse(TaskState.WORKING.isInterrupted());
    }
}
