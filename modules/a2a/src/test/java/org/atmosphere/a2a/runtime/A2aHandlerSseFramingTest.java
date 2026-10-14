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

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code message/stream} request with {@code Accept: text/event-stream}
 * selects cpr's {@link SSEAtmosphereInterceptor}. The A2A handler frames its
 * own SSE events, so the response must reach the wire unchanged: a strict
 * WHATWG event-stream parser (EventSource, httpx-sse) has to dispatch one
 * event per artifact update plus the terminal {@code [DONE]}. Before the fix
 * the interceptor re-prefixed each line with {@code data:} and consumed the
 * blank-line terminators, so a strict parser dispatched nothing usable.
 */
class A2aHandlerSseFramingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AtmosphereFramework framework;
    private TaskManager taskManager;

    static class TwoArtifactAgent {
        @AgentSkill(id = "two", name = "Two", description = "Emits two artifacts")
        @AgentSkillHandler
        public void two(TaskContext task, @AgentSkillParam(name = "message") String message) {
            task.addArtifact(Artifact.text("first"));
            task.addArtifact(Artifact.text("second"));
            task.complete("done");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(Mockito.mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "a2a-sse";
            }

            @Override
            public ServletContext getServletContext() {
                return Mockito.mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
        taskManager = new TaskManager();
    }

    @AfterEach
    void tearDown() {
        taskManager.shutdown();
        framework.destroy();
    }

    @Test
    void messageStreamEmitsSpecValidEventsThroughTheSseInterceptor() throws Exception {
        var registry = new A2aRegistry();
        registry.scan(new TwoArtifactAgent());
        var card = registry.buildAgentCard("agent", "test", "1.0", "/a2a");
        var handler = new A2aHandler(new A2aProtocolHandler(registry, taskManager, card, card));

        var wire = new ByteArrayOutputStream();
        var nativeResponse = Mockito.mock(HttpServletResponse.class);
        Mockito.when(nativeResponse.getOutputStream()).thenReturn(new CapturingStream(wire));
        Mockito.when(nativeResponse.getWriter()).thenReturn(
                new PrintWriter(new OutputStreamWriter(wire, StandardCharsets.UTF_8), true));

        var body = "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"message/stream\","
                + "\"params\":{\"message\":{\"role\":\"user\",\"parts\":[{\"type\":\"text\","
                + "\"text\":\"x\"}],\"messageId\":\"m8\",\"metadata\":{\"skillId\":\"two\"}},"
                + "\"arguments\":{\"message\":\"x\"}}}";
        var request = new AtmosphereRequestImpl.Builder()
                .method("POST")
                .pathInfo("/a2a")
                .inputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                .build();
        request.header("Accept", "text/event-stream");
        request.setAttribute(ApplicationConfig.PROPERTY_USE_STREAM, Boolean.TRUE);
        var response = AtmosphereResponseImpl.newInstance(request);
        response.request(request);
        response.setResponse(nativeResponse);

        var resource = new AtmosphereResourceImpl();
        resource.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, Mockito.mock(AsyncSupport.class), handler);
        // AtmosphereFramework.doCometSupport binds the resource to the request.
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);

        var interceptor = new SSEAtmosphereInterceptor();
        interceptor.configure(framework.getAtmosphereConfig());
        interceptor.inspect(resource);

        handler.onRequest(resource);

        var events = strictSseEvents(wire.toString(StandardCharsets.UTF_8));
        assertEquals(3, events.size(), "one event per artifact update plus [DONE]; wire was: "
                + wire.toString(StandardCharsets.UTF_8).strip());
        assertEquals("first", artifactText(events.get(0)));
        assertEquals("second", artifactText(events.get(1)));
        assertEquals("[DONE]", events.get(2));
    }

    /**
     * A unary call ({@code message/send}) with {@code Accept: text/event-stream}
     * alone answers through {@code AbstractProtocolHandler.writeResponse}: one
     * spec-valid {@code message} event carrying the JSON-RPC response.
     */
    @Test
    void unaryCallWithEventStreamAcceptIsOneSpecValidEvent() throws Exception {
        var registry = new A2aRegistry();
        registry.scan(new TwoArtifactAgent());
        var card = registry.buildAgentCard("agent", "test", "1.0", "/a2a");
        var handler = new A2aHandler(new A2aProtocolHandler(registry, taskManager, card, card));

        var wire = new ByteArrayOutputStream();
        var nativeWriter = new PrintWriter(new OutputStreamWriter(wire, StandardCharsets.UTF_8), true);
        var nativeResponse = Mockito.mock(HttpServletResponse.class);
        Mockito.when(nativeResponse.getOutputStream()).thenReturn(new CapturingStream(wire));
        Mockito.when(nativeResponse.getWriter()).thenReturn(nativeWriter);

        var body = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"role\":\"user\",\"parts\":[{\"type\":\"text\","
                + "\"text\":\"x\"}],\"messageId\":\"m9\",\"metadata\":{\"skillId\":\"two\"}},"
                + "\"arguments\":{\"message\":\"x\"}}}";
        var request = new AtmosphereRequestImpl.Builder()
                .method("POST")
                .pathInfo("/a2a")
                .inputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                .build();
        request.header("Accept", "text/event-stream");
        request.setAttribute(ApplicationConfig.PROPERTY_USE_STREAM, Boolean.TRUE);
        var response = AtmosphereResponseImpl.newInstance(request);
        response.request(request);
        response.setResponse(nativeResponse);

        var resource = new AtmosphereResourceImpl();
        resource.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, Mockito.mock(AsyncSupport.class), handler);
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);

        var interceptor = new SSEAtmosphereInterceptor();
        interceptor.configure(framework.getAtmosphereConfig());
        interceptor.inspect(resource);

        handler.onRequest(resource);

        // A container flushes its writer when the response completes.
        nativeWriter.flush();
        var stream = wire.toString(StandardCharsets.UTF_8);
        var events = strictSseEvents(stream);
        assertEquals(1, events.size(), "one event for the unary response; wire was: " + stream.strip());
        var node = mapper.readTree(events.get(0));
        assertEquals("2.0", node.get("jsonrpc").stringValue());
        assertEquals(9, node.get("id").intValue());
        assertTrue(node.has("result"), "a JSON-RPC result: " + events.get(0));
    }

    private String artifactText(String eventData) {
        var node = mapper.readTree(eventData);
        assertEquals("2.0", node.get("jsonrpc").stringValue());
        assertEquals(8, node.get("id").intValue());
        var parts = node.get("result").get("artifactUpdate").get("artifact").get("parts");
        assertTrue(parts.isArray() && parts.size() == 1, "one text part: " + eventData);
        return parts.get(0).get("text").stringValue();
    }

    /**
     * Event-stream interpretation per the WHATWG HTML spec (9.2.6): lines end
     * at CRLF, LF or CR; a blank line dispatches the buffered data; a
     * {@code data} field appends its value (one leading space stripped) plus
     * LF; other fields and comments do not contribute; data still buffered at
     * end of stream is discarded.
     */
    static List<String> strictSseEvents(String stream) {
        var events = new ArrayList<String>();
        var data = new StringBuilder();
        var lines = stream.split("\r\n|\n|\r", -1);
        // The last element follows the final line terminator: it is an
        // incomplete line and never processed.
        for (int i = 0; i < lines.length - 1; i++) {
            var line = lines[i];
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    data.setLength(data.length() - 1);
                    events.add(data.toString());
                }
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            var colon = line.indexOf(':');
            var field = colon < 0 ? line : line.substring(0, colon);
            var value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("data".equals(field)) {
                data.append(value).append('\n');
            }
        }
        return events;
    }

    private static final class CapturingStream extends ServletOutputStream {
        private final OutputStream sink;

        CapturingStream(OutputStream sink) {
            this.sink = sink;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }

        @Override
        public void write(int b) throws java.io.IOException {
            sink.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            sink.write(b, off, len);
        }
    }
}
