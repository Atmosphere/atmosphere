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
import org.atmosphere.a2a.runtime.A2aHandler;
import org.atmosphere.a2a.runtime.A2aProtocolHandler;
import org.atmosphere.a2a.runtime.A2aSession;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aStreamableHttpTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private A2aHandler handler;

    static class TestAgent {
        @A2aSkill(id = "echo", name = "Echo", description = "Echo input back")
        @A2aTaskHandler
        public void echo(TaskContext task, @A2aParam(name = "text") String text) {
            task.addArtifact(Artifact.text("Echo: " + text));
            task.complete("Echoed");
        }
    }

    @BeforeEach
    void setUp() {
        var registry = new A2aRegistry();
        registry.scan(new TestAgent());
        var taskManager = new TaskManager();
        var agentCard = registry.buildAgentCard("test-agent", "A test agent", "1.0.0", "/a2a");
        var protocolHandler = new A2aProtocolHandler(registry, taskManager, agentCard);
        handler = new A2aHandler(protocolHandler);
    }

    @Test
    void testPostJsonResponse() throws Exception {
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"hello"}],\
                "messageId":"m1"},"arguments":{"text":"world"}}}""";

        var output = new StringWriter();
        var resource = mockResource("POST", body, "application/json", null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        var node = mapper.readTree(output.toString());
        assertEquals(1, node.get("id").asInt());
        assertNotNull(node.get("result"));
        assertNotNull(node.get("result").get("id"));
        assertEquals("COMPLETED", node.get("result").get("status").get("state").asText());
        verify(resource.getResponse()).setContentType("application/json");
        verify(resource.getResponse()).setStatus(200);
    }

    @Test
    void testPostSseResponse() throws Exception {
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"hello"}],\
                "messageId":"m1"},"arguments":{"text":"world"}}}""";

        var output = new StringWriter();
        var resource = mockResource("POST", body, "application/json, text/event-stream", null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        var raw = output.toString();
        assertTrue(raw.startsWith("event: message\ndata: "), "Should be SSE format");
        assertTrue(raw.endsWith("\n\n"), "SSE must end with double newline");
        verify(resource.getResponse()).setContentType("text/event-stream");

        // Extract JSON from SSE
        var json = raw.replace("event: message\ndata: ", "").replace("\n\n", "");
        var node = mapper.readTree(json);
        assertEquals(1, node.get("id").asInt());
        assertNotNull(node.get("result"));
    }

    @Test
    void testPostEmptyBodyReturns400() throws Exception {
        var output = new StringWriter();
        var resource = mockResource("POST", "", "application/json", null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        verify(resource.getResponse()).setStatus(400);
        assertTrue(output.toString().contains("Empty body"));
    }

    @Test
    void testPostNotificationReturns202() throws Exception {
        // A JSON-RPC notification has no "id" field
        var body = """
                {"jsonrpc":"2.0","method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"hello"}],\
                "messageId":"m1"}}}""";

        var resource = mockResource("POST", body, "application/json", null);

        handler.onRequest(resource);

        verify(resource.getResponse()).setStatus(202);
    }

    @Test
    void testGetAgentCardReturnsJson() throws Exception {
        var output = new StringWriter();
        var resource = mockResource("GET", "", null, null);
        when(resource.getRequest().getRequestURI()).thenReturn("/a2a/agent.json");
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        verify(resource.getResponse()).setStatus(200);
        verify(resource.getResponse()).setContentType("application/json");

        var node = mapper.readTree(output.toString());
        assertEquals("test-agent", node.get("name").asText());
        assertEquals("1.0.0", node.get("version").asText());
        assertTrue(node.has("skills"));
        assertTrue(node.get("skills").isArray());
        assertEquals(1, node.get("skills").size());
    }

    @Test
    void testGetWellKnownAgentCard() throws Exception {
        var output = new StringWriter();
        var resource = mockResource("GET", "", null, null);
        when(resource.getRequest().getRequestURI()).thenReturn("/.well-known/agent.json");
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        verify(resource.getResponse()).setStatus(200);
        verify(resource.getResponse()).setContentType("application/json");

        var node = mapper.readTree(output.toString());
        assertEquals("test-agent", node.get("name").asText());
    }

    @Test
    void testGetSseStreamSuspends() throws Exception {
        var output = new StringWriter();
        var resource = mockResource("GET", "", "text/event-stream", null);
        when(resource.getRequest().getRequestURI()).thenReturn("/a2a/stream");
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        verify(resource).suspend();
        verify(resource.getResponse()).setContentType("text/event-stream");
    }

    @Test
    void testDeleteTerminatesSession() throws Exception {
        // First create a session via POST
        var sendBody = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"hello"}],\
                "messageId":"m1"},"arguments":{"text":"world"}}}""";

        var output1 = new StringWriter();
        var resource1 = mockResource("POST", sendBody, "application/json", null);
        when(resource1.getResponse().getWriter()).thenReturn(new PrintWriter(output1));

        handler.onRequest(resource1);

        assertEquals(1, handler.sessions().size());
        var sessionId = handler.sessions().keySet().iterator().next();
        assertNotNull(sessionId);

        // Now DELETE the session
        var resource2 = mockResource("DELETE", "", null, sessionId);

        handler.onRequest(resource2);

        verify(resource2.getResponse()).setStatus(204);
        assertTrue(handler.sessions().isEmpty(), "Session should be removed after DELETE");
    }

    @Test
    void testUnsupportedMethodReturns405() throws Exception {
        var output = new StringWriter();
        var resource = mockResource("PUT", "", null, null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        verify(resource.getResponse()).setStatus(405);
    }

    @Test
    void testSessionIdTracking() throws Exception {
        // First POST creates session
        var sendBody = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",\
                "params":{"message":{"role":"user",\
                "parts":[{"type":"text","text":"hello"}],\
                "messageId":"m1"},"arguments":{"text":"world"}}}""";

        var output1 = new StringWriter();
        var resource1 = mockResource("POST", sendBody, "application/json", null);
        when(resource1.getResponse().getWriter()).thenReturn(new PrintWriter(output1));

        handler.onRequest(resource1);

        assertEquals(1, handler.sessions().size());
        var sessionId = handler.sessions().keySet().iterator().next();

        // Session ID header should be set
        verify(resource1.getResponse()).setHeader(eq("A2a-Session-Id"),
                argThat(s -> s != null && !s.isEmpty()));

        // Second POST with session ID should restore session
        var secondBody = """
                {"jsonrpc":"2.0","id":2,"method":"tasks/list","params":{}}""";
        var output2 = new StringWriter();
        var resource2 = mockResource("POST", secondBody, "application/json", sessionId);
        when(resource2.getResponse().getWriter()).thenReturn(new PrintWriter(output2));

        handler.onRequest(resource2);

        // Session should be restored - verify setAttribute was called with an A2aSession
        verify(resource2.getRequest()).setAttribute(eq(A2aSession.ATTRIBUTE_KEY),
                any(A2aSession.class));

        // Still only one session
        assertEquals(1, handler.sessions().size());
    }

    // ── Helper ───────────────────────────────────────────────────────────

    private AtmosphereResource mockResource(String method, String body,
                                            String acceptHeader, String sessionId)
            throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);

        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.uuid()).thenReturn("test-uuid");

        when(request.getMethod()).thenReturn(method);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        when(request.getHeader("Accept")).thenReturn(acceptHeader);
        when(request.getHeader("A2a-Session-Id")).thenReturn(sessionId);

        // Support setAttribute/getAttribute so A2aSession can be stored and retrieved
        var attributes = new HashMap<String, Object>();
        doAnswer(inv -> {
            attributes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString()))
                .thenAnswer(inv -> attributes.get(inv.getArgument(0, String.class)));

        return resource;
    }
}
