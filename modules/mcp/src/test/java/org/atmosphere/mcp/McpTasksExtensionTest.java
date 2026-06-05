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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.protocol.McpInputContext;
import org.atmosphere.mcp.protocol.McpInputRequiredException;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the {@code io.modelcontextprotocol/tasks} extension (SEP-2663) on
 * the stateless {@code 2026-07-28} dialect: a {@code @McpTool(longRunning=true)}
 * call returns a {@code CreateTaskResult} handle the client polls via
 * {@code tasks/get}, gated on the per-request extension capability (SEP-2133),
 * with {@code -32003} when the client did not negotiate it. No {@code tasks/list}.
 */
public class McpTasksExtensionTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private McpProtocolHandler handler;
    private AtmosphereResource resource;

    public static class TaskServer {
        @McpTool(name = "slow_echo", description = "Echo after a beat", longRunning = true)
        public String slowEcho(@McpParam(name = "text") String text) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "echo:" + text;
        }

        @McpTool(name = "greet", description = "Greet")
        public String greet(@McpParam(name = "name") String name) {
            return "Hi " + name;
        }

        @McpTool(name = "confirm_job", description = "Long job needing confirmation", longRunning = true)
        public String confirmJob(McpInputContext input) {
            if (!input.has("ok")) {
                throw new McpInputRequiredException(Map.of("ok",
                        Map.of("method", "elicitation/create", "params", Map.of("message", "Proceed?"))));
            }
            return "job done ok=" + input.get("ok");
        }
    }

    public static class PlainServer {
        @McpTool(name = "greet", description = "Greet")
        public String greet(@McpParam(name = "name") String name) {
            return "Hi " + name;
        }
    }

    @BeforeEach
    public void setUp() {
        var registry = new McpRegistry();
        registry.scan(new TaskServer());
        handler = new McpProtocolHandler("test-server", "1.0.0", registry, mock(AtmosphereConfig.class));
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("tasks-uuid");
    }

    /** _meta whose clientCapabilities declare the tasks extension. */
    private static String metaWithTasks() {
        return """
                "_meta":{
                    "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                    "io.modelcontextprotocol/clientInfo":{"name":"c","version":"1"},
                    "io.modelcontextprotocol/clientCapabilities":{
                        "extensions":{"io.modelcontextprotocol/tasks":{}}
                    }
                }""";
    }

    /** _meta without the tasks extension declared. */
    private static String metaNoExt() {
        return """
                "_meta":{
                    "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                    "io.modelcontextprotocol/clientInfo":{"name":"c","version":"1"},
                    "io.modelcontextprotocol/clientCapabilities":{}
                }""";
    }

    private JsonNode call(String json) {
        return mapper.readTree(handler.handleMessage(resource, json));
    }

    // ── Task creation ────────────────────────────────────────────────────

    @Test
    public void testLongRunningToolReturnsCreateTaskResult() {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"slow_echo\",\"arguments\":{\"text\":\"hi\"}," + metaWithTasks() + "}}";
        var result = call(req).get("result");
        assertNotNull(result);
        assertEquals("task", result.get("resultType").stringValue(),
                "long-running tool must answer with a task handle, not a CallToolResult");
        assertNotNull(result.get("taskId"));
        assertEquals("working", result.get("status").stringValue());
        assertTrue(result.has("ttlMs"));
        assertTrue(result.has("pollIntervalMs"));
        assertNull(result.get("content"), "a CreateTaskResult is a handle, not the final result");
    }

    @Test
    public void testTaskPollsToCompletion() throws Exception {
        var create = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"slow_echo\",\"arguments\":{\"text\":\"world\"}," + metaWithTasks() + "}}");
        var taskId = create.get("result").get("taskId").stringValue();

        var terminal = pollUntilTerminal(taskId);
        assertEquals("complete", terminal.get("result").get("resultType").stringValue(),
                "tasks/get is itself a completed call");
        assertEquals("completed", terminal.get("result").get("status").stringValue());
        var inner = terminal.get("result").get("result");
        assertEquals("complete", inner.get("resultType").stringValue());
        assertFalse(inner.get("isError").asBoolean());
        assertEquals("echo:world", inner.get("content").get(0).get("text").stringValue());
    }

    @Test
    public void testLongRunningToolWithoutCapabilityRejectedWith32003() {
        // Client did not declare the tasks extension → cannot be served as a task.
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"slow_echo\",\"arguments\":{\"text\":\"hi\"}," + metaNoExt() + "}}";
        var error = call(req).get("error");
        assertNotNull(error);
        assertEquals(-32003, error.get("code").asInt(), "MISSING_REQUIRED_CLIENT_CAPABILITY");
        assertTrue(error.get("data").get("requiredCapabilities").get("extensions")
                .has("io.modelcontextprotocol/tasks"));
    }

    @Test
    public void testNonLongRunningToolStaysSynchronous() {
        // greet is not long-running → normal CallToolResult even with tasks negotiated.
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"greet\",\"arguments\":{\"name\":\"Ada\"}," + metaWithTasks() + "}}";
        var result = call(req).get("result");
        assertEquals("complete", result.get("resultType").stringValue());
        assertEquals("Hi Ada", result.get("content").get(0).get("text").stringValue());
    }

    // ── tasks/get + tasks/cancel ─────────────────────────────────────────

    @Test
    public void testTasksGetUnknownTask() {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/get\",\"params\":{"
                + "\"taskId\":\"does-not-exist\"," + metaWithTasks() + "}}";
        var error = call(req).get("error");
        assertEquals(-32602, error.get("code").asInt());
    }

    @Test
    public void testTasksGetWithoutCapabilityRejected() {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/get\",\"params\":{"
                + "\"taskId\":\"x\"," + metaNoExt() + "}}";
        assertEquals(-32003, call(req).get("error").get("code").asInt());
    }

    @Test
    public void testTasksCancel() {
        var create = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"slow_echo\",\"arguments\":{\"text\":\"x\"}," + metaWithTasks() + "}}");
        var taskId = create.get("result").get("taskId").stringValue();

        var cancel = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/cancel\",\"params\":{"
                + "\"taskId\":\"" + taskId + "\"," + metaWithTasks() + "}}");
        var status = cancel.get("result").get("status").stringValue();
        // The worker may have completed in the race; either way it's terminal.
        assertTrue(status.equals("cancelled") || status.equals("completed"),
                "after cancel the task must be terminal, was: " + status);
    }

    @Test
    public void testTasksUpdateOnWorkingTaskRejected() {
        var create = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"slow_echo\",\"arguments\":{\"text\":\"x\"}," + metaWithTasks() + "}}");
        var taskId = create.get("result").get("taskId").stringValue();
        var update = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/update\",\"params\":{"
                + "\"taskId\":\"" + taskId + "\",\"inputResponses\":{}," + metaWithTasks() + "}}");
        // A working (non-input_required) task rejects tasks/update per its contract.
        assertNotNull(update.get("error"));
    }

    // ── Interactive task: input_required → tasks/update resume (SEP-2663+2322) ──

    @Test
    public void testTaskInputRequiredResumeViaUpdate() throws Exception {
        // A long-running tool that pauses for input parks the task in
        // input_required; tasks/update supplies the response and resumes it.
        var create = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"confirm_job\"," + metaWithTasks() + "}}");
        assertEquals("task", create.get("result").get("resultType").stringValue());
        var taskId = create.get("result").get("taskId").stringValue();

        var paused = pollUntilStatus(taskId, "input_required");
        assertTrue(paused.get("result").get("inputRequests").has("ok"),
                "tasks/get on an input_required task inlines the outstanding requests");

        var update = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/update\",\"params\":{"
                + "\"taskId\":\"" + taskId + "\",\"inputResponses\":{\"ok\":\"yes\"}," + metaWithTasks() + "}}");
        assertNull(update.get("error"), "tasks/update on an input_required task is accepted");

        var done = pollUntilTerminal(taskId);
        assertEquals("completed", done.get("result").get("status").stringValue());
        assertEquals("job done ok=yes",
                done.get("result").get("result").get("content").get(0).get("text").stringValue());
    }

    @Test
    public void testNoTasksList() {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/list\",\"params\":{" + metaWithTasks() + "}}";
        assertEquals(-32601, call(req).get("error").get("code").asInt(),
                "tasks/list is removed on the stateless model (SEP-2663)");
    }

    // ── Discovery advertisement (Runtime Truth) ──────────────────────────

    @Test
    public void testDiscoverAdvertisesTasksExtension() {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{" + metaWithTasks() + "}}";
        var caps = call(req).get("result").get("capabilities");
        assertNotNull(caps.get("extensions"), "server with a long-running tool advertises extensions");
        assertTrue(caps.get("extensions").has("io.modelcontextprotocol/tasks"));
    }

    @Test
    public void testDiscoverOmitsExtensionWhenNoLongRunningTool() {
        var registry = new McpRegistry();
        registry.scan(new PlainServer());
        var plain = new McpProtocolHandler("test-server", "1.0.0", registry, mock(AtmosphereConfig.class));
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{" + metaWithTasks() + "}}";
        var caps = mapper.readTree(plain.handleMessage(resource, req)).get("result").get("capabilities");
        assertNull(caps.get("extensions"),
                "no long-running tool → tasks extension must not be advertised (Runtime Truth)");
    }

    private JsonNode pollUntilTerminal(String taskId) throws Exception {
        for (int i = 0; i < 150; i++) {
            var node = call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tasks/get\",\"params\":{"
                    + "\"taskId\":\"" + taskId + "\"," + metaWithTasks() + "}}");
            var status = node.get("result").get("status").stringValue();
            if (status.equals("completed") || status.equals("failed") || status.equals("cancelled")) {
                return node;
            }
            Thread.sleep(20);
        }
        fail("task " + taskId + " did not reach a terminal status");
        return null;
    }

    private JsonNode pollUntilStatus(String taskId, String target) throws Exception {
        for (int i = 0; i < 150; i++) {
            var node = call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tasks/get\",\"params\":{"
                    + "\"taskId\":\"" + taskId + "\"," + metaWithTasks() + "}}");
            var status = node.get("result").get("status").stringValue();
            if (status.equals(target)) {
                return node;
            }
            if (status.equals("completed") || status.equals("failed") || status.equals("cancelled")) {
                fail("task " + taskId + " reached terminal '" + status + "' before '" + target + "'");
            }
            Thread.sleep(20);
        }
        fail("task " + taskId + " did not reach status '" + target + "'");
        return null;
    }
}
