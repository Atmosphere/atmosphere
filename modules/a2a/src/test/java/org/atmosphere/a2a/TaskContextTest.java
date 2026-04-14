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
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.Task;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.a2a.runtime.TaskContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskContextTest {

    @Test
    void initialStateIsWorking() {
        var ctx = new TaskContext("t1", "ctx1");
        assertEquals(TaskState.WORKING, ctx.state());
    }

    @Test
    void taskIdAndContextIdAreRetained() {
        var ctx = new TaskContext("task-42", "context-7");
        assertEquals("task-42", ctx.taskId());
        assertEquals("context-7", ctx.contextId());
    }

    @Test
    void createdAtMillisIsPositive() {
        var ctx = new TaskContext("t1", "c1");
        assertTrue(ctx.createdAtMillis() > 0);
    }

    @Test
    void addMessageAccumulatesMessages() {
        var ctx = new TaskContext("t1", "c1");
        assertTrue(ctx.messages().isEmpty());

        var msg = Message.agent("hello");
        ctx.addMessage(msg);
        assertEquals(1, ctx.messages().size());
        assertEquals(msg, ctx.messages().get(0));
    }

    @Test
    void updateStatusChangesStateAndMessage() {
        var ctx = new TaskContext("t1", "c1");
        ctx.updateStatus(TaskState.INPUT_REQUIRED, "need more info");
        assertEquals(TaskState.INPUT_REQUIRED, ctx.state());
        assertEquals("need more info", ctx.statusMessage());
    }

    @Test
    void completeSetsFinalState() {
        var ctx = new TaskContext("t1", "c1");
        ctx.complete("done");
        assertEquals(TaskState.COMPLETED, ctx.state());
        assertEquals("done", ctx.statusMessage());
    }

    @Test
    void failSetsFinalState() {
        var ctx = new TaskContext("t1", "c1");
        ctx.fail("error occurred");
        assertEquals(TaskState.FAILED, ctx.state());
        assertEquals("error occurred", ctx.statusMessage());
    }

    @Test
    void cancelSetsFinalState() {
        var ctx = new TaskContext("t1", "c1");
        ctx.cancel("user cancelled");
        assertEquals(TaskState.CANCELED, ctx.state());
        assertEquals("user cancelled", ctx.statusMessage());
    }

    @Test
    void addArtifactAccumulates() {
        var ctx = new TaskContext("t1", "c1");
        assertTrue(ctx.artifacts().isEmpty());

        var artifact = Artifact.text("result");
        ctx.addArtifact(artifact);
        assertEquals(1, ctx.artifacts().size());
    }

    @Test
    void toTaskCreatesSnapshot() {
        var ctx = new TaskContext("t1", "c1");
        ctx.addMessage(Message.user("hi"));
        ctx.updateStatus(TaskState.WORKING, "processing");

        Task task = ctx.toTask();
        assertEquals("t1", task.id());
        assertEquals("c1", task.contextId());
        assertEquals(TaskState.WORKING, task.status().state());
        assertEquals("processing", task.status().message());
        assertEquals(1, task.messages().size());
    }

    @Test
    void metadataIsInitiallyEmpty() {
        var ctx = new TaskContext("t1", "c1");
        assertTrue(ctx.metadata().isEmpty());
    }

    @Test
    void messagesListIsUnmodifiable() {
        var ctx = new TaskContext("t1", "c1");
        var messages = ctx.messages();
        try {
            messages.add(Message.agent("test"));
            assertFalse(true, "Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    void statusMessageInitiallyNull() {
        var ctx = new TaskContext("t1", "c1");
        assertNull(ctx.statusMessage());
    }
}
