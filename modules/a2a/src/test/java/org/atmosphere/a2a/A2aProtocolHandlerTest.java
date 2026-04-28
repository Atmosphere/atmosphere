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

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.runtime.A2aProtocolHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.protocol.JsonRpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link A2aProtocolHandler} dispatch — exercises both v1.0.0
 * PascalCase method names and the pre-1.0 slash-style aliases (which should be
 * canonicalized to the same handlers).
 */
class A2aProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private A2aProtocolHandler handler;
    private TaskManager taskManager;

    static class TestAgent {
        @AgentSkill(id = "greet", name = "Greet", description = "Greet someone")
        @AgentSkillHandler
        public void greet(TaskContext task, @AgentSkillParam(name = "name") String name) {
            task.updateStatus(TaskState.WORKING, "Greeting...");
            task.addArtifact(Artifact.text("Hello, " + name + "!"));
            task.complete("Greeted " + name);
        }
    }

    @BeforeEach
    void setUp() {
        var registry = new A2aRegistry();
        registry.scan(new TestAgent());
        taskManager = new TaskManager();
        var card = registry.buildAgentCard("test-agent", "Test", "1.0", "/a2a");
        handler = new A2aProtocolHandler(registry, taskManager, card, card);
    }

    @AfterEach
    void tearDown() {
        taskManager.shutdown();
    }

    @Test
    void sendMessageReturnsTaskWrappedInResponse() throws Exception {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"m1\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"hello\"}]},\"arguments\":{\"name\":\"World\"}}}";
        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);
        assertEquals("2.0", node.get("jsonrpc").stringValue());
        // SendMessageResponse oneof: result.task
        var task = node.get("result").get("task");
        assertNotNull(task);
        assertEquals("TASK_STATE_COMPLETED",
                task.get("status").get("state").stringValue());
    }

    @Test
    void legacyMessageSendStillWorksViaAlias() throws Exception {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"messageId\":\"m1\",\"role\":\"user\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}]},"
                + "\"arguments\":{\"name\":\"World\"}}}";
        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);
        assertNotNull(node.get("result").get("task"));
    }

    @Test
    void getTaskHonorsHistoryLength() throws Exception {
        var sendReq = sendRequest(1, "SendMessage", "World");
        var sendResp = handler.handleMessage(sendReq);
        var taskId = mapper.readTree(sendResp).get("result").get("task")
                .get("id").stringValue();

        var getReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"GetTask\","
                + "\"params\":{\"id\":\"" + taskId + "\",\"historyLength\":0}}";
        var node = mapper.readTree(handler.handleMessage(getReq));
        assertEquals(taskId, node.get("result").get("id").stringValue());
        assertEquals(0, node.get("result").get("history").size());
    }

    @Test
    void listTasksReturnsPaginatedResponse() throws Exception {
        handler.handleMessage(sendRequest(1, "SendMessage", "Alice"));
        handler.handleMessage(sendRequest(2, "SendMessage", "Bob"));

        var req = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ListTasks\","
                + "\"params\":{\"pageSize\":10}}";
        var node = mapper.readTree(handler.handleMessage(req));
        var result = node.get("result");
        assertTrue(result.get("tasks").isArray());
        assertEquals(2, result.get("totalSize").asInt());
        assertEquals(10, result.get("pageSize").asInt());
    }

    @Test
    void listTasksRespectsPageSize() throws Exception {
        handler.handleMessage(sendRequest(1, "SendMessage", "A"));
        handler.handleMessage(sendRequest(2, "SendMessage", "B"));
        handler.handleMessage(sendRequest(3, "SendMessage", "C"));

        var req = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ListTasks\","
                + "\"params\":{\"pageSize\":2}}";
        var node = mapper.readTree(handler.handleMessage(req));
        assertEquals(2, node.get("result").get("tasks").size());
        assertFalse(node.get("result").get("nextPageToken").stringValue().isEmpty());
    }

    @Test
    void cancelTaskOnUnknownReturnsTaskNotFoundError() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"CancelTask\","
                + "\"params\":{\"id\":\"nosuch\"}}";
        var node = mapper.readTree(handler.handleMessage(req));
        assertEquals(A2aProtocolHandler.ERROR_TASK_NOT_FOUND,
                node.get("error").get("code").asInt());
    }

    @Test
    void subscribeToTaskOnTerminalReturnsUnsupportedOperation() throws Exception {
        var sendResp = handler.handleMessage(sendRequest(1, "SendMessage", "X"));
        var taskId = mapper.readTree(sendResp).get("result").get("task")
                .get("id").stringValue();
        // greet skill completes the task; SubscribeToTask on a terminal task → -32004
        var req = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"SubscribeToTask\","
                + "\"params\":{\"id\":\"" + taskId + "\"}}";
        var node = mapper.readTree(handler.handleMessage(req));
        assertEquals(A2aProtocolHandler.ERROR_UNSUPPORTED_OPERATION,
                node.get("error").get("code").asInt());
    }

    @Test
    void getExtendedAgentCardReturnsConfiguredCard() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GetExtendedAgentCard\"}";
        var node = mapper.readTree(handler.handleMessage(req));
        assertEquals("test-agent", node.get("result").get("name").stringValue());
    }

    @Test
    void getExtendedAgentCardFallsBackToPublicCard() throws Exception {
        var registry = new A2aRegistry();
        registry.scan(new TestAgent());
        var bare = new A2aProtocolHandler(registry, new TaskManager(),
                registry.buildAgentCard("a", "b", "1.0", "/a2a"));
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GetExtendedAgentCard\"}";
        var node = mapper.readTree(bare.handleMessage(req));
        assertEquals("a", node.get("result").get("name").stringValue());
    }

    @Test
    void pushNotificationMethodsReturnNotSupported() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"method\":\"CreateTaskPushNotificationConfig\","
                + "\"params\":{\"taskId\":\"t1\",\"url\":\"https://hook\"}}";
        var node = mapper.readTree(handler.handleMessage(req));
        assertEquals(A2aProtocolHandler.ERROR_PUSH_NOT_SUPPORTED,
                node.get("error").get("code").asInt());
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"UnknownMethod\"}";
        var node = mapper.readTree(handler.handleMessage(req));
        assertEquals(JsonRpc.METHOD_NOT_FOUND, node.get("error").get("code").asInt());
    }

    @Test
    void invalidJsonReturnsParseError() throws Exception {
        var node = mapper.readTree(handler.handleMessage("{not json"));
        assertEquals(JsonRpc.PARSE_ERROR, node.get("error").get("code").asInt());
    }

    @Test
    void streamingMessageProducesTokens() {
        var tokens = new ArrayList<String>();
        var completed = new boolean[]{false};
        handler.handleStreamingMessage(sendRequest(1, "SendStreamingMessage", "Bob"),
                tokens::add, () -> completed[0] = true);
        assertFalse(tokens.isEmpty());
        assertTrue(completed[0]);
    }

    @Test
    void streamingMessageWithNoParamsCompletesExactlyOnce() {
        var count = new int[]{0};
        handler.handleStreamingMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendStreamingMessage\"}",
                t -> {}, () -> count[0]++);
        assertEquals(1, count[0]);
    }

    private String sendRequest(int id, String method, String name) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\","
                + "\"params\":{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"hi\"}]},\"arguments\":{\"name\":\"" + name + "\"}}}";
    }
}
