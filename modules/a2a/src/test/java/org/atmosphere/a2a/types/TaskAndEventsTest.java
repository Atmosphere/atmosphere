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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskAndEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- Task ---

    @Test
    void taskConstruction() {
        var status = new Task.TaskStatus(TaskState.WORKING, "in progress");
        var msg = Message.user("hello");
        var artifact = Artifact.text("output");
        var task = new Task("t1", "ctx-1", status, List.of(msg),
                List.of(artifact), Map.of("priority", "high"));
        assertEquals("t1", task.id());
        assertEquals("ctx-1", task.contextId());
        assertEquals(TaskState.WORKING, task.status().state());
        assertEquals("in progress", task.status().message());
        assertEquals(1, task.messages().size());
        assertEquals(1, task.artifacts().size());
        assertEquals("high", task.metadata().get("priority"));
    }

    @Test
    void taskNullMessagesDefaultsToEmpty() {
        var task = new Task("t1", "ctx", new Task.TaskStatus(TaskState.COMPLETED, null),
                null, null, null);
        assertNotNull(task.messages());
        assertTrue(task.messages().isEmpty());
    }

    @Test
    void taskNullArtifactsDefaultsToEmpty() {
        var task = new Task("t1", "ctx", new Task.TaskStatus(TaskState.COMPLETED, null),
                List.of(), null, null);
        assertNotNull(task.artifacts());
        assertTrue(task.artifacts().isEmpty());
    }

    @Test
    void taskNullMetadataDefaultsToEmpty() {
        var task = new Task("t1", "ctx", new Task.TaskStatus(TaskState.COMPLETED, null),
                List.of(), List.of(), null);
        assertNotNull(task.metadata());
        assertTrue(task.metadata().isEmpty());
    }

    @Test
    void taskMessagesDefensivelyCopied() {
        var msgs = new ArrayList<>(List.of(Message.user("hi")));
        var task = new Task("t1", "ctx", new Task.TaskStatus(TaskState.WORKING, null),
                msgs, null, null);
        msgs.add(Message.agent("bye"));
        assertEquals(1, task.messages().size());
    }

    @Test
    void taskMessagesAreUnmodifiable() {
        var task = new Task("t1", "ctx", new Task.TaskStatus(TaskState.WORKING, null),
                List.of(Message.user("hi")), null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> task.messages().add(Message.agent("nope")));
    }

    // --- TaskStatus ---

    @Test
    void taskStatusHoldsStateAndMessage() {
        var status = new Task.TaskStatus(TaskState.FAILED, "something broke");
        assertEquals(TaskState.FAILED, status.state());
        assertEquals("something broke", status.message());
    }

    @Test
    void taskStatusNullMessage() {
        var status = new Task.TaskStatus(TaskState.COMPLETED, null);
        assertEquals(TaskState.COMPLETED, status.state());
        assertNull(status.message());
    }

    // --- TaskState ---

    @Test
    void taskStateAllValues() {
        var values = TaskState.values();
        assertEquals(7, values.length);
        assertEquals(TaskState.WORKING, TaskState.valueOf("WORKING"));
        assertEquals(TaskState.COMPLETED, TaskState.valueOf("COMPLETED"));
        assertEquals(TaskState.FAILED, TaskState.valueOf("FAILED"));
        assertEquals(TaskState.CANCELED, TaskState.valueOf("CANCELED"));
        assertEquals(TaskState.REJECTED, TaskState.valueOf("REJECTED"));
        assertEquals(TaskState.INPUT_REQUIRED, TaskState.valueOf("INPUT_REQUIRED"));
        assertEquals(TaskState.AUTH_REQUIRED, TaskState.valueOf("AUTH_REQUIRED"));
    }

    @Test
    void taskStateInvalidValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> TaskState.valueOf("UNKNOWN"));
    }

    // --- TaskStatusUpdateEvent ---

    @Test
    void taskStatusUpdateEventConstruction() {
        var status = new Task.TaskStatus(TaskState.COMPLETED, "done");
        var event = new TaskStatusUpdateEvent("t1", status, true);
        assertEquals("t1", event.id());
        assertEquals(TaskState.COMPLETED, event.status().state());
        assertTrue(event.isFinal());
    }

    @Test
    void taskStatusUpdateEventNonFinal() {
        var status = new Task.TaskStatus(TaskState.WORKING, null);
        var event = new TaskStatusUpdateEvent("t2", status, false);
        assertFalse(event.isFinal());
    }

    // --- TaskArtifactUpdateEvent ---

    @Test
    void taskArtifactUpdateEventConstruction() {
        var artifact = Artifact.text("result");
        var event = new TaskArtifactUpdateEvent("t1", artifact);
        assertEquals("t1", event.id());
        assertNotNull(event.artifact());
        assertEquals(1, event.artifact().parts().size());
    }

    // --- Skill ---

    @Test
    void skillConstruction() {
        var skill = new Skill("s1", "Search", "Web search skill",
                List.of("search", "web"), Map.of("type", "object"), Map.of("type", "string"));
        assertEquals("s1", skill.id());
        assertEquals("Search", skill.name());
        assertEquals("Web search skill", skill.description());
        assertEquals(2, skill.tags().size());
        assertEquals("object", skill.inputSchema().get("type"));
        assertEquals("string", skill.outputSchema().get("type"));
    }

    @Test
    void skillNullTagsDefaultsToEmpty() {
        var skill = new Skill("s1", "S", "d", null, null, null);
        assertNotNull(skill.tags());
        assertTrue(skill.tags().isEmpty());
    }

    @Test
    void skillNullSchemasDefaultToEmpty() {
        var skill = new Skill("s1", "S", "d", null, null, null);
        assertTrue(skill.inputSchema().isEmpty());
        assertTrue(skill.outputSchema().isEmpty());
    }

    @Test
    void skillTagsAreUnmodifiable() {
        var skill = new Skill("s1", "S", "d", List.of("tag"), null, null);
        assertThrows(UnsupportedOperationException.class, () -> skill.tags().add("new"));
    }

    // --- Artifact ---

    @Test
    void artifactConstruction() {
        var parts = List.<Part>of(new Part.TextPart("content"));
        var artifact = new Artifact("a1", "Report", "A report", parts, Map.of("ver", "1"));
        assertEquals("a1", artifact.artifactId());
        assertEquals("Report", artifact.name());
        assertEquals("A report", artifact.description());
        assertEquals(1, artifact.parts().size());
        assertEquals("1", artifact.metadata().get("ver"));
    }

    @Test
    void artifactNullPartsDefaultsToEmpty() {
        var artifact = new Artifact("a1", null, null, null, null);
        assertTrue(artifact.parts().isEmpty());
        assertTrue(artifact.metadata().isEmpty());
    }

    @Test
    void artifactTextFactory() {
        var artifact = Artifact.text("hello world");
        assertNotNull(artifact.artifactId());
        assertEquals(36, artifact.artifactId().length());
        assertNull(artifact.name());
        assertEquals(1, artifact.parts().size());
        var textPart = (Part.TextPart) artifact.parts().getFirst();
        assertEquals("hello world", textPart.text());
    }

    @Test
    void artifactNamedFactory() {
        var parts = List.<Part>of(new Part.TextPart("data"), new Part.TextPart("more"));
        var artifact = Artifact.named("MyArtifact", "desc", parts);
        assertNotNull(artifact.artifactId());
        assertEquals("MyArtifact", artifact.name());
        assertEquals("desc", artifact.description());
        assertEquals(2, artifact.parts().size());
        assertTrue(artifact.metadata().isEmpty());
    }

    // --- JSON round-trip ---

    @Test
    void taskJsonRoundTrip() throws Exception {
        var task = new Task("t1", "ctx-1",
                new Task.TaskStatus(TaskState.WORKING, "processing"),
                List.of(Message.user("input")),
                List.of(Artifact.text("output")),
                Map.of("env", "test"));
        String json = mapper.writeValueAsString(task);
        var deserialized = mapper.readValue(json, Task.class);
        assertEquals("t1", deserialized.id());
        assertEquals("ctx-1", deserialized.contextId());
        assertEquals(TaskState.WORKING, deserialized.status().state());
        assertEquals("processing", deserialized.status().message());
        assertEquals(1, deserialized.messages().size());
        assertEquals(1, deserialized.artifacts().size());
    }

    @Test
    void skillJsonRoundTrip() throws Exception {
        var skill = new Skill("s1", "Translate", "Translates text",
                List.of("nlp"), Map.of("type", "object"), Map.of());
        String json = mapper.writeValueAsString(skill);
        var deserialized = mapper.readValue(json, Skill.class);
        assertEquals("s1", deserialized.id());
        assertEquals("Translate", deserialized.name());
        assertEquals(List.of("nlp"), deserialized.tags());
    }

    @Test
    void artifactJsonRoundTrip() throws Exception {
        var artifact = Artifact.named("doc", "A document",
                List.of(new Part.TextPart("content")));
        String json = mapper.writeValueAsString(artifact);
        var deserialized = mapper.readValue(json, Artifact.class);
        assertEquals("doc", deserialized.name());
        assertEquals(1, deserialized.parts().size());
    }
}
