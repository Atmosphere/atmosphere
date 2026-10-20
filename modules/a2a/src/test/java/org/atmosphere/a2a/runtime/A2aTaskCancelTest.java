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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.Task;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.a2a.types.TaskStatusUpdateEvent;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CancelTask} on a running task stops the skill's turn: the model call
 * in flight is cancelled, no tool runs after the cancel, and the task stays
 * {@code TASK_STATE_CANCELED} — the skill's later complete() does not
 * overwrite it, and the {@code SendMessage} that started the task answers
 * CANCELED. A {@code SendStreamingMessage} stream starts with the Task, so a
 * streaming client has the task id to cancel, and streams the answer as it is
 * produced.
 */
class A2aTaskCancelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TaskManager taskManager;
    private A2aProtocolHandler handler;
    private SlowModelAgent agent;

    /** A skill driving a slow streaming model, then a tool, like the sample's ask skill. */
    public static final class SlowModelAgent {
        final CountDownLatch streaming = new CountDownLatch(3);
        final AtomicBoolean modelCancelled = new AtomicBoolean();
        final AtomicInteger toolRuns = new AtomicInteger();
        final AtomicReference<TaskContext> task = new AtomicReference<>();

        @AgentSkill(id = "essay", name = "Essay", description = "Writes slowly")
        @AgentSkillHandler
        public void essay(TaskContext task, @AgentSkillParam(name = "message") String message) {
            this.task.set(task);
            task.updateStatus(TaskState.WORKING, "Writing...");
            var answer = new StringBuilder();
            // The model call: a token every 50 ms for 10 s, interruptible as an
            // HTTP read is.
            try {
                for (int i = 0; i < 200; i++) {
                    var token = "t" + i + " ";
                    answer.append(token);
                    task.emitToken(token);
                    streaming.countDown();
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                modelCancelled.set(true);
                Thread.currentThread().interrupt();
            }
            // The tool the skill calls with its answer, through the framework.
            var tool = ToolDefinition.builder("publish", "Publish the essay")
                    .parameter("text", "the essay", "string")
                    .executor(args -> {
                        toolRuns.incrementAndGet();
                        return "published";
                    })
                    .build();
            ToolExecutionHelper.executeWithApproval("publish", tool,
                    Map.of("text", answer.toString()), new A2aStreamCollector(task, null), null);
            task.addArtifact(Artifact.text(answer.toString()));
            task.complete("Essay written");
        }
    }

    @BeforeEach
    void setUp() {
        taskManager = new TaskManager();
        var registry = new A2aRegistry();
        agent = new SlowModelAgent();
        registry.scan(agent);
        var card = registry.buildAgentCard("agent", "test", "1.0", "/a2a");
        handler = new A2aProtocolHandler(registry, taskManager, card, card);
    }

    @AfterEach
    void tearDown() {
        taskManager.shutdown();
    }

    private String rpc(int id, String method, String params) {
        return handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}");
    }

    private static final String SEND_PARAMS = "{\"message\":{\"messageId\":\"m1\",\"role\":\"ROLE_USER\","
            + "\"parts\":[{\"text\":\"essay\"}],\"metadata\":{\"skillId\":\"essay\"}},"
            + "\"arguments\":{\"message\":\"Write an essay\"}}";

    @Test
    void cancelTaskStopsTheModelCallAndTheTaskStaysCanceled() throws Exception {
        var sent = CompletableFuture.supplyAsync(() -> rpc(1, "SendMessage", SEND_PARAMS));
        assertTrue(agent.streaming.await(5, TimeUnit.SECONDS), "the model call is streaming");
        var taskId = agent.task.get().taskId();

        var cancel = mapper.readTree(rpc(2, "CancelTask", "{\"id\":\"" + taskId + "\"}"));
        assertEquals("TASK_STATE_CANCELED", cancel.get("result").get("status").get("state").stringValue());

        // The SendMessage returns as soon as the cancel stopped the model call.
        var response = mapper.readTree(sent.get(3, TimeUnit.SECONDS));
        var task = response.get("result").get("task");
        assertEquals("TASK_STATE_CANCELED", task.get("status").get("state").stringValue(),
                "the skill's later complete() overwrote the cancel: " + task);
        assertTrue(task.get("artifacts") == null || task.get("artifacts").isEmpty(),
                "an artifact was added after the cancel: " + task);
        assertTrue(agent.modelCancelled.get(), "the model call was not cancelled");
        assertEquals(0, agent.toolRuns.get(), "a tool ran after the cancel");

        var get = mapper.readTree(rpc(3, "GetTask", "{\"id\":\"" + taskId + "\"}"));
        assertEquals("TASK_STATE_CANCELED", get.get("result").get("status").get("state").stringValue());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void aTerminalStateIsNeverOverwritten() {
        var ctx = taskManager.createTask("ctx");
        ctx.updateStatus(TaskState.WORKING, "working");
        ctx.cancel("Canceled by request");
        ctx.complete("done");
        ctx.fail("boom");
        ctx.updateStatus(TaskState.WORKING, "again");
        assertEquals(TaskState.CANCELED, ctx.state());
        assertEquals("Canceled by request", ctx.statusMessage());

        var done = taskManager.createTask("ctx");
        done.complete("done");
        done.fail("late failure");
        assertEquals(TaskState.COMPLETED, done.state());
    }

    @Test
    void theStreamStartsWithTheTaskAndStreamsTheAnswerAsItIsProduced() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var taskFirst = new AtomicReference<Task>();
        var tokensBeforeEnd = new AtomicInteger();
        var finished = new CountDownLatch(1);
        var body = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"SendStreamingMessage\",\"params\":"
                + SEND_PARAMS + "}";
        Thread.startVirtualThread(() -> handler.handleStreamingMessage(body, new A2aStreamSink() {
            @Override
            public void task(Task task) {
                taskFirst.compareAndSet(null, task);
                events.add("task");
            }

            @Override
            public void token(String text) {
                events.add("token");
                tokensBeforeEnd.incrementAndGet();
            }

            @Override
            public void status(TaskStatusUpdateEvent update) {
                events.add("status:" + update.status().state());
            }

            @Override
            public void complete() {
                finished.countDown();
            }
        }));

        // The answer arrives token by token while the model call still runs.
        assertTrue(agent.streaming.await(5, TimeUnit.SECONDS));
        assertEquals("task", events.get(0), "the stream begins with the Task: " + events);
        assertTrue(tokensBeforeEnd.get() >= 3, "tokens stream as produced: " + events);
        assertEquals(1, finished.getCount(), "the stream is still open while the model runs");

        // The streaming client cancels with the id the first frame gave it.
        rpc(6, "CancelTask", "{\"id\":\"" + taskFirst.get().id() + "\"}");
        assertTrue(finished.await(3, TimeUnit.SECONDS), "the stream ends after the cancel");
        assertEquals("status:CANCELED", events.get(events.size() - 1), events.toString());
        assertEquals(1, events.stream().filter("task"::equals).count());
        assertEquals(0, agent.toolRuns.get(), "a tool ran after the cancel");
    }

    @Test
    void aSkillThatStreamsItsAnswerIsNotSentTheWholeAnswerAgain() {
        var ctx = taskManager.createTask("ctx");
        var sent = new CopyOnWriteArrayList<String>();
        ctx.setTokenSink(sent::add);
        ctx.emitToken("Hello ");
        ctx.emitToken("world");
        ctx.addArtifact(Artifact.text("Hello world"));
        assertEquals(List.of("Hello ", "world"), sent);
        assertEquals(1, ctx.artifacts().size(), "the artifact is still recorded on the task");
    }
}
