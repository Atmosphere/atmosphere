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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStatusUpdateEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void carriesTaskAndContextIds() {
        var event = new TaskStatusUpdateEvent("t1", "ctx1",
                TaskStatus.of(TaskState.WORKING, "processing"));
        assertEquals("t1", event.taskId());
        assertEquals("ctx1", event.contextId());
        assertEquals(TaskState.WORKING, event.status().state());
    }

    @Test
    void serializationOmitsNullMetadata() throws Exception {
        var event = new TaskStatusUpdateEvent("t1", "ctx1",
                TaskStatus.of(TaskState.COMPLETED));
        var json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"taskId\":\"t1\""));
        assertTrue(json.contains("\"contextId\":\"ctx1\""));
        assertEquals(false, json.contains("\"metadata\""));
    }

    @Test
    void differentTaskIdsAreNotEqual() {
        var s = TaskStatus.of(TaskState.WORKING);
        var a = new TaskStatusUpdateEvent("t1", "ctx", s);
        var b = new TaskStatusUpdateEvent("t2", "ctx", s);
        assertNotEquals(a.taskId(), b.taskId());
    }

    @Test
    void metadataPreserved() {
        var event = new TaskStatusUpdateEvent("t1", "ctx1",
                TaskStatus.of(TaskState.WORKING),
                Map.of("retry", 2));
        assertEquals(2, event.metadata().get("retry"));
    }
}
