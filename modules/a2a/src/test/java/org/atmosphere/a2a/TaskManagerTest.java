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

import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.TaskArtifactUpdateEvent;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.a2a.types.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerTest {

    @Test
    void createAndRetrieveTask() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");
        assertNotNull(ctx.taskId());
        assertEquals("ctx-1", ctx.contextId());
        assertEquals(TaskState.WORKING, ctx.state());

        var retrieved = mgr.getTask(ctx.taskId());
        assertTrue(retrieved.isPresent());
        assertEquals(ctx.taskId(), retrieved.get().taskId());
    }

    @Test
    void listTasksByContext() {
        var mgr = new TaskManager();
        mgr.createTask("ctx-1");
        mgr.createTask("ctx-1");
        mgr.createTask("ctx-2");

        assertEquals(2, mgr.listTasks("ctx-1").size());
        assertEquals(1, mgr.listTasks("ctx-2").size());
        assertEquals(3, mgr.listTasks(null).size());
    }

    @Test
    void cancelTask() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");
        assertTrue(mgr.cancelTask(ctx.taskId()));
        assertEquals(TaskState.CANCELED, ctx.state());
        assertFalse(mgr.cancelTask(ctx.taskId())); // already canceled
    }

    @Test
    void statusUpdateNotifiesListeners() {
        var mgr = new TaskManager();
        var eventRef = new AtomicReference<TaskStatusUpdateEvent>();
        mgr.onStatusUpdate(eventRef::set);

        var ctx = mgr.createTask("ctx-1");
        ctx.updateStatus(TaskState.WORKING, "Processing...");

        assertNotNull(eventRef.get());
        assertEquals(ctx.taskId(), eventRef.get().id());
        assertEquals(TaskState.WORKING, eventRef.get().status().state());
        assertFalse(eventRef.get().isFinal());
    }

    @Test
    void completeTaskIsFinal() {
        var mgr = new TaskManager();
        var eventRef = new AtomicReference<TaskStatusUpdateEvent>();
        mgr.onStatusUpdate(eventRef::set);

        var ctx = mgr.createTask("ctx-1");
        ctx.complete("Done");

        assertNotNull(eventRef.get());
        assertTrue(eventRef.get().isFinal());
        assertEquals(TaskState.COMPLETED, ctx.state());
    }

    @Test
    void artifactUpdateNotifiesListeners() {
        var mgr = new TaskManager();
        var eventRef = new AtomicReference<TaskArtifactUpdateEvent>();
        mgr.onArtifactUpdate(eventRef::set);

        var ctx = mgr.createTask("ctx-1");
        ctx.addArtifact(Artifact.text("result"));

        assertNotNull(eventRef.get());
        assertEquals(ctx.taskId(), eventRef.get().id());
        assertNotNull(eventRef.get().artifact());
    }

    @Test
    void taskToTaskConversion() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");
        ctx.addMessage(Message.user("hello"));
        ctx.addArtifact(Artifact.text("result"));
        ctx.complete("Done");

        var task = ctx.toTask();
        assertEquals(ctx.taskId(), task.id());
        assertEquals("ctx-1", task.contextId());
        assertEquals(1, task.messages().size());
        assertEquals(1, task.artifacts().size());
        assertEquals(TaskState.COMPLETED, task.status().state());
    }
}
