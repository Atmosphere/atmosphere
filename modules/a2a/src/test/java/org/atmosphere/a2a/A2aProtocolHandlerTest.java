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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.runtime.A2aProtocolHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.protocol.JsonRpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private A2aProtocolHandler handler;

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
        var taskManager = new TaskManager();
        var agentCard = registry.buildAgentCard("test-agent", "Test", "1.0", "/a2a");
        handler = new A2aProtocolHandler(registry, taskManager, agentCard);
    }

    @Test
    void handleGetAgentCard() throws Exception {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"method\":\"agent/authenticatedExtendedCard\"}";
        var response = handler.handleMessage(request);
        assertNotNull(response);
        var node = mapper.readTree(response);
        assertEquals("2.0", node.get("jsonrpc").asText());
        assertNotNull(node.get("result"));
        assertEquals("test-agent", node.get("result").get("name").asText());
    }

    @Test
    void handleSendMessage() throws Exception {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"role\":\"user\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}],"
                + "\"messageId\":\"m1\"},\"arguments\":{\"name\":\"World\"}}}";
        var response = handler.handleMessage(request);
        assertNotNull(response);
        var node = mapper.readTree(response);
        assertNotNull(node.get("result"));
        var task = node.get("result");
        assertNotNull(task.get("id"));
        assertEquals("COMPLETED", task.get("status").get("state").asText());
    }

    @Test
    void handleGetTask() throws Exception {
        // First create a task
        var sendReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"role\":\"user\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"test\"}],"
                + "\"messageId\":\"m1\"},\"arguments\":{\"name\":\"World\"}}}";
        var sendResp = handler.handleMessage(sendReq);
        var taskId = mapper.readTree(sendResp).get("result").get("id").asText();

        // Then get it
        var getReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/get\","
                + "\"params\":{\"id\":\"" + taskId + "\"}}";
        var getResp = handler.handleMessage(getReq);
        var node = mapper.readTree(getResp);
        assertEquals(taskId, node.get("result").get("id").asText());
    }

    @Test
    void handleListTasks() throws Exception {
        var sendReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"role\":\"user\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"test\"}],"
                + "\"messageId\":\"m1\"},\"arguments\":{\"name\":\"World\"}}}";
        handler.handleMessage(sendReq);

        var listReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/list\","
                + "\"params\":{}}";
        var listResp = handler.handleMessage(listReq);
        var node = mapper.readTree(listResp);
        assertTrue(node.get("result").isArray());
        assertTrue(node.get("result").size() > 0);
    }

    @Test
    void handleUnknownMethod() throws Exception {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"unknown/method\"}";
        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);
        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.METHOD_NOT_FOUND, node.get("error").get("code").asInt());
    }

    @Test
    void handleInvalidJson() throws Exception {
        var response = handler.handleMessage("{invalid");
        var node = mapper.readTree(response);
        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.PARSE_ERROR, node.get("error").get("code").asInt());
    }

    @Test
    void handleStreamingMessageDispatchesSameAsSend() throws Exception {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/stream\","
                + "\"params\":{\"message\":{\"role\":\"user\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}],"
                + "\"messageId\":\"m1\"},\"arguments\":{\"name\":\"World\"}}}";
        var response = handler.handleMessage(request);
        assertNotNull(response);
        var node = mapper.readTree(response);
        // Should succeed (not METHOD_NOT_FOUND) — same behavior as message/send
        assertNotNull(node.get("result"));
        assertEquals("COMPLETED", node.get("result").get("status").get("state").asText());
    }

    @Test
    void handleStreamingMessageCallbackProducesTokens() {
        var tokens = new ArrayList<String>();
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/stream\","
                + "\"params\":{\"message\":{\"role\":\"user\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}],"
                + "\"messageId\":\"m1\"},\"arguments\":{\"name\":\"World\"}}}";

        var completed = new boolean[]{false};
        handler.handleStreamingMessage(request, tokens::add, () -> completed[0] = true);

        assertFalse(tokens.isEmpty(), "Should have emitted at least one token");
        assertTrue(completed[0], "onComplete should have been called");
    }

    @Test
    void handleStreamingMessageWithNoParamsCallsComplete() {
        var tokens = new ArrayList<String>();
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/stream\"}";

        var completed = new boolean[]{false};
        handler.handleStreamingMessage(request, tokens::add, () -> completed[0] = true);

        assertTrue(tokens.isEmpty());
        assertTrue(completed[0], "onComplete must be called even with no params");
    }

    @Test
    void handleStreamingMessageNoParamsCallsOnCompleteExactlyOnce() {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/stream\"}";
        var completeCount = new int[]{0};
        handler.handleStreamingMessage(request, t -> {}, () -> completeCount[0]++);
        assertEquals(1, completeCount[0],
                "onComplete must be called exactly once, even with no params");
    }

    @Test
    void handleStreamingMessageWithNoSkillsProducesNoTokens() {
        // Build a handler with an empty registry (no skills)
        var emptyRegistry = new A2aRegistry();
        var taskManager = new TaskManager();
        var card = emptyRegistry.buildAgentCard("empty", "Empty", "1.0", "/a2a");
        var emptyHandler = new A2aProtocolHandler(emptyRegistry, taskManager, card);

        var tokens = new ArrayList<String>();
        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/stream\","
                + "\"params\":{\"message\":{\"role\":\"user\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}],"
                + "\"messageId\":\"m1\"}}}";

        var completed = new boolean[]{false};
        emptyHandler.handleStreamingMessage(request, tokens::add, () -> completed[0] = true);

        assertTrue(tokens.isEmpty(), "No skills means no tokens");
        assertTrue(completed[0], "onComplete must still be called");
    }
}
