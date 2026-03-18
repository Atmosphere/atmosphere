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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.annotation.A2aParam;
import org.atmosphere.a2a.annotation.A2aServer;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aMultiSkillTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private A2aProtocolHandler handler;

    @A2aServer(name = "multi-skill-agent", endpoint = "/a2a")
    static class MultiSkillAgent {
        @A2aSkill(id = "greet", name = "Greet", description = "Greet someone", tags = {"social"})
        @A2aTaskHandler
        public void greet(TaskContext task, @A2aParam(name = "name") String name) {
            task.updateStatus(TaskState.WORKING, "Greeting...");
            task.addArtifact(Artifact.text("Hello, " + name + "!"));
            task.complete("Greeted");
        }

        @A2aSkill(id = "compute", name = "Compute", description = "Compute a sum", tags = {"math"})
        @A2aTaskHandler
        public void compute(TaskContext task,
                            @A2aParam(name = "a") String a,
                            @A2aParam(name = "b") String b) {
            int sum = Integer.parseInt(a) + Integer.parseInt(b);
            task.addArtifact(Artifact.text("Sum: " + sum));
            task.complete("Computed");
        }

        @A2aSkill(id = "failing", name = "Failing", description = "Always fails")
        @A2aTaskHandler
        public void failing(TaskContext task) {
            throw new RuntimeException("Intentional failure");
        }
    }

    @BeforeEach
    void setUp() {
        var registry = new A2aRegistry();
        registry.scan(new MultiSkillAgent());
        var taskManager = new TaskManager();
        var agentCard = registry.buildAgentCard("multi-skill-agent", "Multi-skill", "1.0", "/a2a");
        handler = new A2aProtocolHandler(registry, taskManager, agentCard);
    }

    @Test
    void testGreetSkillExecution() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"greet me"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"greet"}},\
                "arguments":{"name":"Alice"}}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertNotNull(node.get("result"));
        var task = node.get("result");
        assertEquals("COMPLETED", task.get("status").get("state").asText());
        assertEquals("Greeted", task.get("status").get("message").asText());

        // Verify artifact contains the greeting
        assertTrue(task.has("artifacts"));
        var artifacts = task.get("artifacts");
        assertTrue(artifacts.isArray());
        assertEquals(1, artifacts.size());
        var parts = artifacts.get(0).get("parts");
        assertEquals("Hello, Alice!", parts.get(0).get("text").asText());
    }

    @Test
    void testComputeSkillWithArguments() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"compute"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"compute"}},\
                "arguments":{"a":"17","b":"25"}}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        var task = node.get("result");
        assertEquals("COMPLETED", task.get("status").get("state").asText());
        var parts = task.get("artifacts").get(0).get("parts");
        assertEquals("Sum: 42", parts.get(0).get("text").asText());
    }

    @Test
    void testFailingSkillSetsTaskFailed() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"fail"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"failing"}}}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        var task = node.get("result");
        assertEquals("FAILED", task.get("status").get("state").asText());
        assertEquals("Intentional failure", task.get("status").get("message").asText());
    }

    @Test
    void testDefaultSkillWhenNoSkillIdSpecified() throws Exception {
        // When no skillId is in metadata, the first registered skill is used as default.
        // ConcurrentHashMap iteration order is non-deterministic, so we just verify
        // that a task was created and dispatched (not rejected with "No skills registered").
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"hello"}],\
                "messageId":"m1"},\
                "arguments":{"name":"Bob"}}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        var task = node.get("result");
        assertNotNull(task.get("id"), "Task should be created");
        assertNotNull(task.get("status"), "Task should have a status");
        var statusMsg = task.get("status").get("message").asText();
        // The key assertion: it should NOT fail with "No skills registered"
        assertFalse(statusMsg.contains("No skills registered"),
                "A default skill should be selected when no skillId is specified");
    }

    @Test
    void testAgentCardContainsAllSkills() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"agent/authenticatedExtendedCard"}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        var card = node.get("result");
        assertNotNull(card);
        assertEquals("multi-skill-agent", card.get("name").asText());

        var skills = card.get("skills");
        assertTrue(skills.isArray());
        assertEquals(3, skills.size());

        // Verify each skill has required fields
        for (JsonNode skill : skills) {
            assertNotNull(skill.get("id"));
            assertNotNull(skill.get("name"));
            assertNotNull(skill.get("description"));
        }

        // Verify capabilities
        var caps = card.get("capabilities");
        assertTrue(caps.get("streaming").asBoolean());
        assertTrue(caps.get("stateTransitionHistory").asBoolean());
    }

    @Test
    void testListTasksAfterMultipleExecutions() throws Exception {
        // Execute greet skill
        var greetReq = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"greet"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"greet"}},\
                "arguments":{"name":"Alice"}}}""";
        handler.handleMessage(greetReq);

        // Execute compute skill
        var computeReq = """
                {"jsonrpc":"2.0","id":2,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"compute"}],\
                "messageId":"m2",\
                "metadata":{"skillId":"compute"}},\
                "arguments":{"a":"3","b":"4"}}}""";
        handler.handleMessage(computeReq);

        // Execute failing skill
        var failReq = """
                {"jsonrpc":"2.0","id":3,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"fail"}],\
                "messageId":"m3",\
                "metadata":{"skillId":"failing"}}}}""";
        handler.handleMessage(failReq);

        // List all tasks
        var listReq = """
                {"jsonrpc":"2.0","id":4,"method":"tasks/list","params":{}}""";
        var listResp = handler.handleMessage(listReq);
        var node = mapper.readTree(listResp);

        var tasks = node.get("result");
        assertTrue(tasks.isArray());
        assertEquals(3, tasks.size());
    }

    @Test
    void testCancelWorkingTask() throws Exception {
        // Create a task that completes (we still get its ID)
        var sendReq = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"greet"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"greet"}},\
                "arguments":{"name":"Alice"}}}""";
        var sendResp = handler.handleMessage(sendReq);
        var taskId = mapper.readTree(sendResp).get("result").get("id").asText();

        // Try to cancel it - since it's already COMPLETED, cancel should fail
        var cancelReq = """
                {"jsonrpc":"2.0","id":2,"method":"tasks/cancel",\
                "params":{"id":"%s"}}""".formatted(taskId);
        var cancelResp = handler.handleMessage(cancelReq);
        var cancelNode = mapper.readTree(cancelResp);

        // Cancel should return error for already completed task
        assertNotNull(cancelNode.get("error"));
        assertTrue(cancelNode.get("error").get("message").asText().contains("not cancellable"));
    }

    @Test
    void testGetTaskById() throws Exception {
        // Create a task
        var sendReq = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"greet"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"greet"}},\
                "arguments":{"name":"Charlie"}}}""";
        var sendResp = handler.handleMessage(sendReq);
        var taskId = mapper.readTree(sendResp).get("result").get("id").asText();

        // Get it by ID
        var getReq = """
                {"jsonrpc":"2.0","id":2,"method":"tasks/get",\
                "params":{"id":"%s"}}""".formatted(taskId);
        var getResp = handler.handleMessage(getReq);
        var node = mapper.readTree(getResp);

        var task = node.get("result");
        assertEquals(taskId, task.get("id").asText());
        assertEquals("COMPLETED", task.get("status").get("state").asText());
        assertTrue(task.get("artifacts").size() > 0);
    }

    @Test
    void testUnknownMethodReturnsError() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"nonexistent/method"}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.METHOD_NOT_FOUND, node.get("error").get("code").asInt());
        assertTrue(node.get("error").get("message").asText().contains("Unknown method"));
    }

    @Test
    void testGetNonExistentTaskReturnsError() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tasks/get",\
                "params":{"id":"non-existent-task-id"}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.METHOD_NOT_FOUND, node.get("error").get("code").asInt());
        assertTrue(node.get("error").get("message").asText().contains("Unknown task"));
    }

    @Test
    void testSendMessageMissingParamsReturnsError() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"message/send"}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.INVALID_PARAMS, node.get("error").get("code").asInt());
        assertTrue(node.get("error").get("message").asText().contains("Missing params"));
    }

    @Test
    void testCancelTaskMissingIdReturnsError() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tasks/cancel","params":{}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.INVALID_PARAMS, node.get("error").get("code").asInt());
    }

    @Test
    void testGetTaskMissingIdReturnsError() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tasks/get","params":{}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.INVALID_PARAMS, node.get("error").get("code").asInt());
    }

    @Test
    void testListTasksWithContextFilter() throws Exception {
        // Create tasks with specific contextIds
        var req1 = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"contextId":"ctx-alpha","message":{"role":"user",\
                "parts":[{"type":"text","text":"greet"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"greet"}},\
                "arguments":{"name":"A"}}}""";
        handler.handleMessage(req1);

        var req2 = """
                {"jsonrpc":"2.0","id":2,"method":"message/send",\
                "params":{"contextId":"ctx-beta","message":{"role":"user",\
                "parts":[{"type":"text","text":"greet"}],\
                "messageId":"m2",\
                "metadata":{"skillId":"greet"}},\
                "arguments":{"name":"B"}}}""";
        handler.handleMessage(req2);

        var req3 = """
                {"jsonrpc":"2.0","id":3,"method":"message/send",\
                "params":{"contextId":"ctx-alpha","message":{"role":"user",\
                "parts":[{"type":"text","text":"greet"}],\
                "messageId":"m3",\
                "metadata":{"skillId":"greet"}},\
                "arguments":{"name":"C"}}}""";
        handler.handleMessage(req3);

        // List tasks filtered by contextId "ctx-alpha"
        var listReq = """
                {"jsonrpc":"2.0","id":4,"method":"tasks/list",\
                "params":{"contextId":"ctx-alpha"}}""";
        var listResp = handler.handleMessage(listReq);
        var node = mapper.readTree(listResp);

        var tasks = node.get("result");
        assertTrue(tasks.isArray());
        assertEquals(2, tasks.size());
        for (JsonNode task : tasks) {
            assertEquals("ctx-alpha", task.get("contextId").asText());
        }
    }

    @Test
    void testUnknownSkillIdSetsTaskFailed() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"hello"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"nonexistent-skill"}}}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        var task = node.get("result");
        assertEquals("FAILED", task.get("status").get("state").asText());
        assertTrue(task.get("status").get("message").asText().contains("Unknown skill"));
    }

    @Test
    void testInvalidJsonReturnsParseError() throws Exception {
        var response = handler.handleMessage("{not valid json at all");
        var node = mapper.readTree(response);

        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.PARSE_ERROR, node.get("error").get("code").asInt());
    }

    @Test
    void testMissingMethodReturnsInvalidRequest() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertNotNull(node.get("error"));
        assertEquals(JsonRpc.INVALID_REQUEST, node.get("error").get("code").asInt());
        assertTrue(node.get("error").get("message").asText().contains("Missing method"));
    }

    @Test
    void testNumericRequestId() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":42,"method":"agent/authenticatedExtendedCard"}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertEquals(42, node.get("id").asInt());
        assertNotNull(node.get("result"));
    }

    @Test
    void testStringRequestId() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":"req-abc","method":"agent/authenticatedExtendedCard"}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        assertEquals("req-abc", node.get("id").asText());
        assertNotNull(node.get("result"));
    }

    @Test
    void testTaskIncludesUserMessage() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"hi there"}],\
                "messageId":"m1",\
                "metadata":{"skillId":"greet"}},\
                "arguments":{"name":"Dave"}}}""";

        var response = handler.handleMessage(request);
        var node = mapper.readTree(response);

        var messages = node.get("result").get("messages");
        assertTrue(messages.isArray());
        assertFalse(messages.isEmpty());

        // The user message should be included in the task
        var firstMessage = messages.get(0);
        assertEquals("user", firstMessage.get("role").asText());
    }
}
