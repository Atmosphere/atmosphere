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
    void createTaskReturnsTaskWithSubmittedInitialState() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx1");
        assertNotNull(ctx.taskId());
        assertEquals(TaskState.SUBMITTED, ctx.state());
        mgr.shutdown();
    }

    @Test
    void getTaskFindsCreatedTask() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx1");
        assertEquals(ctx.taskId(), mgr.getTask(ctx.taskId()).orElseThrow().taskId());
        mgr.shutdown();
    }

    @Test
    void cancelTaskOnSubmittedSucceeds() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx1");
        assertTrue(mgr.cancelTask(ctx.taskId()));
        assertEquals(TaskState.CANCELED, ctx.state());
        mgr.shutdown();
    }

    @Test
    void cancelTaskOnTerminalReturnsFalse() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx1");
        ctx.complete("done");
        assertFalse(mgr.cancelTask(ctx.taskId()));
        mgr.shutdown();
    }

    @Test
    void statusEventEmittedWithContextId() {
        var mgr = new TaskManager();
        var captured = new AtomicReference<TaskStatusUpdateEvent>();
        mgr.onStatusUpdate(captured::set);

        var ctx = mgr.createTask("ctx-A");
        ctx.updateStatus(TaskState.WORKING, "starting");

        var ev = captured.get();
        assertNotNull(ev);
        assertEquals(ctx.taskId(), ev.taskId());
        assertEquals("ctx-A", ev.contextId());
        assertEquals(TaskState.WORKING, ev.status().state());
        mgr.shutdown();
    }

    @Test
    void artifactEventEmittedWithContextId() {
        var mgr = new TaskManager();
        var captured = new AtomicReference<TaskArtifactUpdateEvent>();
        mgr.onArtifactUpdate(captured::set);

        var ctx = mgr.createTask("ctx-B");
        ctx.addArtifact(Artifact.text("output"));

        var ev = captured.get();
        assertNotNull(ev);
        assertEquals(ctx.taskId(), ev.taskId());
        assertEquals("ctx-B", ev.contextId());
        assertNotNull(ev.artifact());
        mgr.shutdown();
    }

    @Test
    void listTasksFiltersByContext() {
        var mgr = new TaskManager();
        mgr.createTask("ctx-A");
        mgr.createTask("ctx-A");
        mgr.createTask("ctx-B");

        assertEquals(2, mgr.listTasks("ctx-A").size());
        assertEquals(1, mgr.listTasks("ctx-B").size());
        assertEquals(3, mgr.listTasks(null).size());
        mgr.shutdown();
    }

    @Test
    void listTasksFiltersByStatus() {
        var mgr = new TaskManager();
        var ctx1 = mgr.createTask("ctx");
        var ctx2 = mgr.createTask("ctx");
        ctx1.updateStatus(TaskState.WORKING, "");
        ctx2.complete("done");

        assertEquals(1, mgr.listTasks(null, TaskState.WORKING).size());
        assertEquals(1, mgr.listTasks(null, TaskState.COMPLETED).size());
        mgr.shutdown();
    }
}
