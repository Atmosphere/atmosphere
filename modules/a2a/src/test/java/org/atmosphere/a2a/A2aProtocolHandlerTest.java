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
import org.atmosphere.a2a.annotation.A2aParam;
import org.atmosphere.a2a.annotation.A2aSkill;
import org.atmosphere.a2a.annotation.A2aTaskHandler;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.runtime.A2aProtocolHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.protocol.JsonRpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private A2aProtocolHandler handler;

    static class TestAgent {
        @A2aSkill(id = "greet", name = "Greet", description = "Greet someone")
        @A2aTaskHandler
        public void greet(TaskContext task, @A2aParam(name = "name") String name) {
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
}
