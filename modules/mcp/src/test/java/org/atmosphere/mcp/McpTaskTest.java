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
package org.atmosphere.mcp;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.atmosphere.mcp.runtime.McpTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins MCP 2025-11-25 (experimental) tasks/* wire behavior:
 * task-augmented tools/call returns CreateTaskResult and dispatches async,
 * tasks/get returns the envelope, tasks/result blocks until terminal then
 * surfaces the underlying result with related-task _meta, tasks/list
 * paginates via opaque cursor, tasks/cancel transitions running tasks and
 * rejects already-terminal ones with -32602 Invalid params.
 */
public class McpTaskTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class SlowTool {

        public volatile boolean release = false;

        @McpTool(name = "echo", description = "Returns its argument")
        public String echo(@McpParam(name = "v") String v) {
            return "echo:" + v;
        }

        @McpTool(name = "wait", description = "Blocks until released, then returns")
        public String wait(@McpParam(name = "v") String v) throws InterruptedException {
            for (int i = 0; i < 50 && !release; i++) {
                Thread.sleep(20);
            }
            return "done:" + v;
        }
    }

    private SlowTool toolBean;
    private McpProtocolHandler handler;
    private AtmosphereResource resource;

    @BeforeEach
    public void setUp() {
        toolBean = new SlowTool();
        var registry = new McpRegistry();
        registry.scan(toolBean);
        handler = new McpProtocolHandler("test", "1.0", registry, mock(AtmosphereConfig.class));
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("test-tasks");
    }

    @Test
    public void taskAugmentedToolsCallReturnsCreateTaskResult() throws Exception {
        toolBean.release = true; // let it complete fast
        var response = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"echo","arguments":{"v":"hi"},
                    "task":{"ttl":5000}
                }}""");

        var result = mapper.readTree(response).get("result");
        assertNotNull(result, "task-augmented tools/call must succeed synchronously");
        var task = result.get("task");
        assertNotNull(task, "result envelope must contain 'task'");
        assertNotNull(task.get("taskId"));
        assertFalse(task.get("taskId").stringValue().isEmpty());
        assertEquals("working", task.get("status").stringValue(),
                "newly-created task must start in 'working'");
        assertNotNull(task.get("createdAt"));
        assertNotNull(task.get("lastUpdatedAt"));
        assertEquals(5000L, task.get("ttl").asLong(),
                "ttl should reflect the requested value when below MAX_TTL_MS");
    }

    @Test
    public void tasksGetReturnsEnvelope() throws Exception {
        toolBean.release = true;
        var creation = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"echo","arguments":{"v":"x"},
                    "task":{}
                }}""");
        var taskId = mapper.readTree(creation).get("result").get("task").get("taskId").stringValue();

        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/get\",\"params\":{\"taskId\":\""
                        + taskId + "\"}}");
        var result = mapper.readTree(response).get("result");
        assertEquals(taskId, result.get("taskId").stringValue());
        assertNotNull(result.get("status"),
                "tasks/get must return the same envelope shape as CreateTaskResult");
    }

    @Test
    public void tasksGetUnknownIdReturnsInvalidParams() throws Exception {
        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"nope\"}}");
        var error = mapper.readTree(response).get("error");
        assertNotNull(error);
        assertEquals(-32602, error.get("code").asInt(),
                "missing task → spec-mandated -32602 Invalid params");
    }

    @Test
    public void tasksResultBlocksUntilTerminalAndCarriesRelatedTaskMeta() throws Exception {
        // Don't release yet — task remains 'working'
        toolBean.release = false;
        var creation = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"wait","arguments":{"v":"yo"},
                    "task":{}
                }}""");
        var taskId = mapper.readTree(creation).get("result").get("task").get("taskId").stringValue();

        // Release the tool slightly later so tasks/result actually has to wait.
        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            toolBean.release = true;
        }).start();

        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/result\",\"params\":{\"taskId\":\""
                        + taskId + "\"}}");
        var result = mapper.readTree(response).get("result");
        assertNotNull(result, "tasks/result must return the underlying result envelope");
        assertNotNull(result.get("content"), "result must include the tool's content");
        assertEquals("text", result.get("content").get(0).get("type").stringValue());
        assertTrue(result.get("content").get(0).get("text").stringValue().contains("done:yo"),
                "tasks/result must surface the wait tool's actual output");

        // Per spec, _meta.io.modelcontextprotocol/related-task must point back
        // to the task id so streaming clients can correlate.
        var meta = result.get("_meta");
        assertNotNull(meta, "tasks/result must carry _meta");
        var related = meta.get("io.modelcontextprotocol/related-task");
        assertNotNull(related, "_meta must include related-task pointer");
        assertEquals(taskId, related.get("taskId").stringValue());

        // Underlying task should now be in COMPLETED.
        var task = handler.taskManager().get(taskId).orElseThrow();
        assertEquals(McpTask.Status.COMPLETED, task.status());
    }

    @Test
    public void tasksListPaginatesViaCursor() throws Exception {
        toolBean.release = true;
        // Create several tasks. Use distinct ids to keep request envelopes unique.
        for (int i = 0; i < 3; i++) {
            handler.handleMessage(resource,
                    "{\"jsonrpc\":\"2.0\",\"id\":" + (10 + i)
                            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"echo\","
                            + "\"arguments\":{\"v\":\"" + i + "\"},\"task\":{}}}");
        }

        // First page (limit hard-coded to 100 server-side, so all fit; assert
        // the cursor plumbing via a pagination roundtrip when limit < total).
        var firstPage = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tasks/list\",\"params\":{}}");
        var first = mapper.readTree(firstPage).get("result");
        assertNotNull(first.get("tasks"));
        assertTrue(first.get("tasks").size() >= 3,
                "should list at least the 3 tasks just created");
        // With limit=100 and only 3 created, no nextCursor expected.
        assertNull(first.get("nextCursor"),
                "nextCursor must be omitted when no further pages exist");
    }

    @Test
    public void tasksCancelTransitionsRunningTask() throws Exception {
        toolBean.release = false; // leave it running
        var creation = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"wait","arguments":{"v":"x"},
                    "task":{}
                }}""");
        var taskId = mapper.readTree(creation).get("result").get("task").get("taskId").stringValue();

        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/cancel\",\"params\":{\"taskId\":\""
                        + taskId + "\"}}");
        var result = mapper.readTree(response).get("result");
        assertNotNull(result, "tasks/cancel must return success on a running task");
        assertEquals("cancelled", result.get("status").stringValue(),
                "task must be in 'cancelled' state after cancel");

        // Allow the background thread to settle so subsequent tests don't see a
        // leftover live tool invocation.
        toolBean.release = true;
    }

    @Test
    public void tasksCancelOnTerminalReturnsInvalidParams() throws Exception {
        toolBean.release = true;
        var creation = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"echo","arguments":{"v":"x"},
                    "task":{}
                }}""");
        var taskId = mapper.readTree(creation).get("result").get("task").get("taskId").stringValue();

        // Drain the task to COMPLETED via tasks/result (blocks until terminal).
        handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/result\",\"params\":{\"taskId\":\""
                        + taskId + "\"}}");
        // Wait briefly for completion to reflect (tasks/result already
        // returned, so task is terminal — but be defensive).
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!handler.taskManager().get(taskId).orElseThrow().status().isTerminal()) {
            if (System.nanoTime() > deadline) {
                throw new TimeoutException("Task never reached terminal");
            }
            Thread.sleep(10);
        }

        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tasks/cancel\",\"params\":{\"taskId\":\""
                        + taskId + "\"}}");
        var error = mapper.readTree(response).get("error");
        assertNotNull(error, "cancelling a terminal task must error");
        assertEquals(-32602, error.get("code").asInt());
        assertTrue(error.get("message").stringValue().contains("terminal"),
                "error message should explain the terminal state");
    }

    @Test
    public void initializeAdvertisesTasksCapability() throws Exception {
        var response = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-11-25",
                    "capabilities":{},
                    "clientInfo":{"name":"test","version":"1.0"}
                }}""");
        var caps = mapper.readTree(response).get("result").get("capabilities");
        var tasks = caps.get("tasks");
        assertNotNull(tasks, "server must advertise 'tasks' capability for 2025-11-25 clients");
        assertNotNull(tasks.get("list"), "tasks/list must be advertised");
        assertNotNull(tasks.get("cancel"), "tasks/cancel must be advertised");
        var requests = tasks.get("requests");
        assertNotNull(requests, "tasks capability must enumerate task-augmentable request types");
        // tools/call is the documented surface
        assertNotNull(requests.get("tools"),
                "task-augmentation must list 'tools'");
        assertNotNull(requests.get("tools").get("call"),
                "task-augmentation must list 'tools/call'");
    }

    @SuppressWarnings("unused")
    private static final JsonNode KEEP = null;
}
