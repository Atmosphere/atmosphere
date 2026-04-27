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

import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.TaskState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskContextTest {

    @Test
    void initialStateIsSubmittedPerV1Spec() {
        var ctx = new TaskContext("t1", "ctx1");
        assertEquals(TaskState.SUBMITTED, ctx.state());
    }

    @Test
    void taskAndContextIdsRetained() {
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
    void addMessageAccumulates() {
        var ctx = new TaskContext("t1", "c1");
        var msg = Message.agent("hello");
        ctx.addMessage(msg);
        assertEquals(1, ctx.messages().size());
    }

    @Test
    void updateStatusFlipsState() {
        var ctx = new TaskContext("t1", "c1");
        ctx.updateStatus(TaskState.WORKING, "go");
        assertEquals(TaskState.WORKING, ctx.state());
        assertEquals("go", ctx.statusMessage());
    }

    @Test
    void completeIsTerminal() {
        var ctx = new TaskContext("t1", "c1");
        ctx.complete("done");
        assertTrue(ctx.state().isTerminal());
        assertEquals(TaskState.COMPLETED, ctx.state());
    }

    @Test
    void failIsTerminal() {
        var ctx = new TaskContext("t1", "c1");
        ctx.fail("nope");
        assertEquals(TaskState.FAILED, ctx.state());
        assertEquals("nope", ctx.statusMessage());
    }

    @Test
    void cancelIsTerminal() {
        var ctx = new TaskContext("t1", "c1");
        ctx.cancel("user");
        assertEquals(TaskState.CANCELED, ctx.state());
    }

    @Test
    void addArtifactAccumulates() {
        var ctx = new TaskContext("t1", "c1");
        ctx.addArtifact(Artifact.text("result"));
        assertEquals(1, ctx.artifacts().size());
    }

    @Test
    void toTaskUsesV1Schema() {
        var ctx = new TaskContext("t1", "c1");
        ctx.addMessage(Message.user("hi"));
        ctx.updateStatus(TaskState.WORKING, "processing");

        var task = ctx.toTask();
        assertEquals("t1", task.id());
        assertEquals("c1", task.contextId());
        assertEquals(TaskState.WORKING, task.status().state());
        assertNotNull(task.status().timestamp());
        assertEquals("processing", task.status().message().parts().getFirst().text());
        assertEquals(1, task.history().size());
    }

    @Test
    void toTaskWithHistoryLengthClampsHistory() {
        var ctx = new TaskContext("t1", "c1");
        ctx.addMessage(Message.user("first"));
        ctx.addMessage(Message.user("second"));
        ctx.addMessage(Message.user("third"));

        var task = ctx.toTask(2);
        assertEquals(2, task.history().size());
        assertEquals("second", task.history().get(0).parts().getFirst().text());
        assertEquals("third", task.history().get(1).parts().getFirst().text());
    }

    @Test
    void toTaskWithZeroHistoryReturnsEmpty() {
        var ctx = new TaskContext("t1", "c1");
        ctx.addMessage(Message.user("first"));
        var task = ctx.toTask(0);
        assertEquals(0, task.history().size());
    }

    @Test
    void messagesListIsImmutable() {
        var ctx = new TaskContext("t1", "c1");
        var messages = ctx.messages();
        assertThrows(UnsupportedOperationException.class,
                () -> messages.add(Message.agent("test")));
    }

    @Test
    void statusMessageInitiallyNull() {
        var ctx = new TaskContext("t1", "c1");
        assertNull(ctx.statusMessage());
    }

    @Test
    void metadataInitiallyEmpty() {
        var ctx = new TaskContext("t1", "c1");
        assertTrue(ctx.metadata().isEmpty());
    }
}
