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
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.Part;
import org.atmosphere.a2a.types.TaskArtifactUpdateEvent;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.a2a.types.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aTaskLifecycleTest {

    @Test
    void testTaskCreatedInWorkingState() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        assertEquals(TaskState.WORKING, ctx.state());
        assertNotNull(ctx.taskId());
        assertEquals("ctx-1", ctx.contextId());
        assertNull(ctx.statusMessage());
        assertTrue(ctx.messages().isEmpty());
        assertTrue(ctx.artifacts().isEmpty());
        assertTrue(ctx.metadata().isEmpty());
    }

    @Test
    void testTaskTransitionsToCompleted() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        assertEquals(TaskState.WORKING, ctx.state());

        ctx.complete("All done");

        assertEquals(TaskState.COMPLETED, ctx.state());
        assertEquals("All done", ctx.statusMessage());
    }

    @Test
    void testTaskTransitionsToFailed() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        ctx.fail("Something went wrong");

        assertEquals(TaskState.FAILED, ctx.state());
        assertEquals("Something went wrong", ctx.statusMessage());
    }

    @Test
    void testTaskTransitionsToCanceled() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        ctx.cancel("User canceled");

        assertEquals(TaskState.CANCELED, ctx.state());
        assertEquals("User canceled", ctx.statusMessage());
    }

    @Test
    void testTaskTransitionsViaUpdateStatus() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        ctx.updateStatus(TaskState.INPUT_REQUIRED, "Need more info");
        assertEquals(TaskState.INPUT_REQUIRED, ctx.state());
        assertEquals("Need more info", ctx.statusMessage());

        ctx.updateStatus(TaskState.AUTH_REQUIRED, "Authentication needed");
        assertEquals(TaskState.AUTH_REQUIRED, ctx.state());

        ctx.updateStatus(TaskState.REJECTED, "Request rejected");
        assertEquals(TaskState.REJECTED, ctx.state());
    }

    @Test
    void testTaskWithMultipleArtifacts() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        var artifact1 = Artifact.text("First result");
        var artifact2 = Artifact.text("Second result");
        var artifact3 = Artifact.named("report", "Final report",
                List.of(new Part.TextPart("Report content")));

        ctx.addArtifact(artifact1);
        ctx.addArtifact(artifact2);
        ctx.addArtifact(artifact3);

        assertEquals(3, ctx.artifacts().size());

        var task = ctx.toTask();
        assertEquals(3, task.artifacts().size());

        // Verify artifact IDs are unique
        var ids = task.artifacts().stream()
                .map(Artifact::artifactId)
                .distinct()
                .count();
        assertEquals(3, ids);
    }

    @Test
    void testTaskWithMultipleMessages() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        ctx.addMessage(Message.user("Hello, agent!"));
        ctx.addMessage(Message.agent("Hello! How can I help?"));
        ctx.addMessage(Message.user("Please compute 2+2."));
        ctx.addMessage(Message.agent("The result is 4."));

        assertEquals(4, ctx.messages().size());
        assertEquals("user", ctx.messages().get(0).role());
        assertEquals("agent", ctx.messages().get(1).role());
        assertEquals("user", ctx.messages().get(2).role());
        assertEquals("agent", ctx.messages().get(3).role());

        var task = ctx.toTask();
        assertEquals(4, task.messages().size());
    }

    @Test
    void testConcurrentTaskCreation() throws Exception {
        var mgr = new TaskManager();
        var taskCount = 100;
        var latch = new CountDownLatch(taskCount);
        var taskIds = ConcurrentHashMap.newKeySet();

        // Create 100 tasks concurrently using virtual threads
        for (int i = 0; i < taskCount; i++) {
            var contextId = "ctx-" + i;
            Thread.ofVirtual().start(() -> {
                try {
                    var ctx = mgr.createTask(contextId);
                    taskIds.add(ctx.taskId());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete within timeout");
        assertEquals(taskCount, taskIds.size(), "All task IDs should be unique");
        assertEquals(taskCount, mgr.listTasks(null).size());
    }

    @Test
    void testStatusListenerCalledOnEveryTransition() {
        var mgr = new TaskManager();
        var events = new CopyOnWriteArrayList<TaskStatusUpdateEvent>();
        mgr.onStatusUpdate(events::add);

        var ctx = mgr.createTask("ctx-1");

        // WORKING transition
        ctx.updateStatus(TaskState.WORKING, "Processing...");
        assertEquals(1, events.size());
        assertEquals(TaskState.WORKING, events.get(0).status().state());
        assertFalse(events.get(0).isFinal());

        // COMPLETED transition
        ctx.complete("Done");
        assertEquals(2, events.size());
        assertEquals(TaskState.COMPLETED, events.get(1).status().state());
        assertTrue(events.get(1).isFinal());
    }

    @Test
    void testStatusListenerCalledForAllFinalStates() {
        var mgr = new TaskManager();
        var events = new CopyOnWriteArrayList<TaskStatusUpdateEvent>();
        mgr.onStatusUpdate(events::add);

        // FAILED is final
        var ctx1 = mgr.createTask("ctx-1");
        ctx1.fail("Error");
        assertTrue(events.getLast().isFinal());

        // CANCELED is final
        var ctx2 = mgr.createTask("ctx-2");
        ctx2.cancel("Canceled");
        assertTrue(events.getLast().isFinal());

        // REJECTED is final
        var ctx3 = mgr.createTask("ctx-3");
        ctx3.updateStatus(TaskState.REJECTED, "Rejected");
        assertTrue(events.getLast().isFinal());

        // INPUT_REQUIRED is NOT final
        var ctx4 = mgr.createTask("ctx-4");
        ctx4.updateStatus(TaskState.INPUT_REQUIRED, "Need input");
        assertFalse(events.getLast().isFinal());

        // AUTH_REQUIRED is NOT final
        var ctx5 = mgr.createTask("ctx-5");
        ctx5.updateStatus(TaskState.AUTH_REQUIRED, "Need auth");
        assertFalse(events.getLast().isFinal());
    }

    @Test
    void testArtifactListenerCalledOnAdd() {
        var mgr = new TaskManager();
        var events = new CopyOnWriteArrayList<TaskArtifactUpdateEvent>();
        mgr.onArtifactUpdate(events::add);

        var ctx = mgr.createTask("ctx-1");

        var artifact1 = Artifact.text("result-1");
        var artifact2 = Artifact.text("result-2");

        ctx.addArtifact(artifact1);
        assertEquals(1, events.size());
        assertEquals(ctx.taskId(), events.get(0).id());
        assertNotNull(events.get(0).artifact());

        ctx.addArtifact(artifact2);
        assertEquals(2, events.size());
        assertEquals(ctx.taskId(), events.get(1).id());
    }

    @Test
    void testMultipleStatusListeners() {
        var mgr = new TaskManager();
        var events1 = new CopyOnWriteArrayList<TaskStatusUpdateEvent>();
        var events2 = new CopyOnWriteArrayList<TaskStatusUpdateEvent>();
        mgr.onStatusUpdate(events1::add);
        mgr.onStatusUpdate(events2::add);

        var ctx = mgr.createTask("ctx-1");
        ctx.complete("Done");

        assertEquals(1, events1.size());
        assertEquals(1, events2.size());
        assertEquals(ctx.taskId(), events1.get(0).id());
        assertEquals(ctx.taskId(), events2.get(0).id());
    }

    @Test
    void testMultipleArtifactListeners() {
        var mgr = new TaskManager();
        var events1 = new CopyOnWriteArrayList<TaskArtifactUpdateEvent>();
        var events2 = new CopyOnWriteArrayList<TaskArtifactUpdateEvent>();
        mgr.onArtifactUpdate(events1::add);
        mgr.onArtifactUpdate(events2::add);

        var ctx = mgr.createTask("ctx-1");
        ctx.addArtifact(Artifact.text("result"));

        assertEquals(1, events1.size());
        assertEquals(1, events2.size());
    }

    @Test
    void testToTaskCreatesSnapshot() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");
        ctx.addMessage(Message.user("hello"));
        ctx.addArtifact(Artifact.text("result"));
        ctx.complete("Done");

        var task = ctx.toTask();

        // Verify snapshot has correct values
        assertEquals(ctx.taskId(), task.id());
        assertEquals("ctx-1", task.contextId());
        assertEquals(TaskState.COMPLETED, task.status().state());
        assertEquals("Done", task.status().message());
        assertEquals(1, task.messages().size());
        assertEquals(1, task.artifacts().size());

        // Task record fields are immutable (List.copyOf and Map.copyOf in constructor)
        assertThrows(UnsupportedOperationException.class, () -> task.messages().add(Message.user("test")));
        assertThrows(UnsupportedOperationException.class, () -> task.artifacts().add(Artifact.text("test")));
        assertThrows(UnsupportedOperationException.class, () -> task.metadata().put("key", "value"));
    }

    @Test
    void testToTaskReflectsLatestState() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        // Take snapshot while WORKING
        var task1 = ctx.toTask();
        assertEquals(TaskState.WORKING, task1.status().state());

        // Modify state
        ctx.addMessage(Message.user("hello"));
        ctx.addArtifact(Artifact.text("result"));
        ctx.complete("Done");

        // Take another snapshot
        var task2 = ctx.toTask();
        assertEquals(TaskState.COMPLETED, task2.status().state());
        assertEquals(1, task2.messages().size());
        assertEquals(1, task2.artifacts().size());

        // Earlier snapshot should still reflect original state
        assertEquals(TaskState.WORKING, task1.status().state());
        assertEquals(0, task1.messages().size());
    }

    @Test
    void testMessagesListIsUnmodifiable() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");
        ctx.addMessage(Message.user("hello"));

        var messages = ctx.messages();
        assertThrows(UnsupportedOperationException.class,
                () -> messages.add(Message.user("sneaky")));
    }

    @Test
    void testArtifactsListIsUnmodifiable() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");
        ctx.addArtifact(Artifact.text("result"));

        var artifacts = ctx.artifacts();
        assertThrows(UnsupportedOperationException.class,
                () -> artifacts.add(Artifact.text("sneaky")));
    }

    @Test
    void testMetadataMapIsUnmodifiable() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        var metadata = ctx.metadata();
        assertThrows(UnsupportedOperationException.class,
                () -> metadata.put("key", "value"));
    }

    @Test
    void testTaskWithFileAndDataParts() {
        var mgr = new TaskManager();
        var ctx = mgr.createTask("ctx-1");

        var filePart = new Part.FilePart("report.pdf", "application/pdf", "file:///reports/q1.pdf");
        var dataPart = new Part.DataPart(Map.of("score", 95, "grade", "A"), Map.of());

        var artifact = Artifact.named("mixed", "Mixed content",
                List.of(new Part.TextPart("Summary"), filePart, dataPart));
        ctx.addArtifact(artifact);

        var task = ctx.toTask();
        assertEquals(1, task.artifacts().size());
        assertEquals(3, task.artifacts().get(0).parts().size());

        var parts = task.artifacts().get(0).parts();
        assertTrue(parts.get(0) instanceof Part.TextPart);
        assertTrue(parts.get(1) instanceof Part.FilePart);
        assertTrue(parts.get(2) instanceof Part.DataPart);
    }

    @Test
    void testStatusListenerExceptionDoesNotPreventOtherListeners() {
        var mgr = new TaskManager();
        var events = new CopyOnWriteArrayList<TaskStatusUpdateEvent>();

        // First listener throws
        mgr.onStatusUpdate(e -> {
            throw new RuntimeException("Listener failure");
        });
        // Second listener should still be called
        mgr.onStatusUpdate(events::add);

        var ctx = mgr.createTask("ctx-1");
        ctx.complete("Done");

        // The second listener should have received the event despite the first throwing
        assertEquals(1, events.size());
        assertEquals(TaskState.COMPLETED, events.get(0).status().state());
    }

    @Test
    void testArtifactListenerExceptionDoesNotPreventOtherListeners() {
        var mgr = new TaskManager();
        var events = new CopyOnWriteArrayList<TaskArtifactUpdateEvent>();

        // First listener throws
        mgr.onArtifactUpdate(e -> {
            throw new RuntimeException("Listener failure");
        });
        // Second listener should still be called
        mgr.onArtifactUpdate(events::add);

        var ctx = mgr.createTask("ctx-1");
        ctx.addArtifact(Artifact.text("result"));

        assertEquals(1, events.size());
        assertNotNull(events.get(0).artifact());
    }

    @Test
    void testTaskContextWithoutTaskManagerDoesNotNotify() {
        // Create a TaskContext directly without going through TaskManager
        var ctx = new TaskContext("manual-id", "ctx-1");

        // These should not throw even though taskManager is null
        ctx.updateStatus(TaskState.WORKING, "Processing");
        ctx.addArtifact(Artifact.text("result"));
        ctx.complete("Done");

        assertEquals(TaskState.COMPLETED, ctx.state());
        assertEquals(1, ctx.artifacts().size());
    }

    @Test
    void testCancelOnlyWorksForWorkingTasks() {
        var mgr = new TaskManager();

        // WORKING task can be canceled
        var ctx1 = mgr.createTask("ctx-1");
        assertTrue(mgr.cancelTask(ctx1.taskId()));
        assertEquals(TaskState.CANCELED, ctx1.state());

        // Already CANCELED task cannot be canceled again
        assertFalse(mgr.cancelTask(ctx1.taskId()));

        // COMPLETED task cannot be canceled
        var ctx2 = mgr.createTask("ctx-2");
        ctx2.complete("Done");
        assertFalse(mgr.cancelTask(ctx2.taskId()));

        // FAILED task cannot be canceled
        var ctx3 = mgr.createTask("ctx-3");
        ctx3.fail("Error");
        assertFalse(mgr.cancelTask(ctx3.taskId()));

        // Nonexistent task cannot be canceled
        assertFalse(mgr.cancelTask("nonexistent-id"));
    }

    @Test
    void testGetNonExistentTaskReturnsEmpty() {
        var mgr = new TaskManager();
        assertTrue(mgr.getTask("nonexistent").isEmpty());
    }

    @Test
    void testListTasksWithNoTasks() {
        var mgr = new TaskManager();
        assertTrue(mgr.listTasks(null).isEmpty());
        assertTrue(mgr.listTasks("ctx-1").isEmpty());
    }
}
