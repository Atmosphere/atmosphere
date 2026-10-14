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

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpHandler;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.atmosphere.mcp.runtime.McpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * An MCP client that sends {@code Accept: text/event-stream} alone selects
 * cpr's {@link SSEAtmosphereInterceptor}. The handler frames its own SSE
 * events, so a POST response and the notifications replayed on the GET stream
 * must reach the wire unchanged: a WHATWG event-stream parser has to dispatch
 * exactly one {@code message} event per JSON-RPC payload.
 */
class McpHandlerSseFramingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AtmosphereFramework framework;
    private McpHandler handler;

    public static class EchoServer {
        @McpTool(name = "echo", description = "Echo input")
        public String echo(@McpParam(name = "text") String text) {
            return text;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(Mockito.mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "mcp-sse";
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
        var registry = new McpRegistry();
        registry.scan(new EchoServer());
        handler = new McpHandler(new McpProtocolHandler("test-server", "1.0.0", registry,
                framework.getAtmosphereConfig()));
    }

    @AfterEach
    void tearDown() {
        handler.destroy();
        framework.destroy();
    }

    @Test
    void postResponseIsOneSpecValidEventThroughTheSseInterceptor() throws Exception {
        var exchange = exchange("POST", "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}", null);

        handler.onRequest(exchange.resource());

        var wire = exchange.completed();
        var events = strictSseEvents(wire);
        assertEquals(1, events.size(), "one message event; wire was: " + wire.strip());
        assertEquals("message", events.get(0).type());
        var node = mapper.readTree(events.get(0).data());
        assertEquals(7, node.get("id").intValue());
        assertNotNull(node.get("result"), events.get(0).data());
    }

    @Test
    void replayedNotificationsAreSpecValidEventsOnTheGetStream() throws Exception {
        var init = exchange("POST", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}", null);
        handler.onRequest(init.resource());
        assertEquals(1, handler.sessions().size(), "initialize must open a session");
        McpSession session = handler.sessions().values().iterator().next();
        session.addPendingNotification("{\"jsonrpc\":\"2.0\",\"method\":\"n1\"}");
        session.addPendingNotification("{\"jsonrpc\":\"2.0\",\"method\":\"n2\"}");

        var get = exchange("GET", "", session.sessionId());
        handler.onRequest(get.resource());

        var wire = get.completed();
        assertEquals(List.of(new Event("message", "{\"jsonrpc\":\"2.0\",\"method\":\"n1\"}"),
                        new Event("message", "{\"jsonrpc\":\"2.0\",\"method\":\"n2\"}")),
                strictSseEvents(wire), "wire was: " + wire.strip());
    }

    /**
     * An HTTP exchange as {@code AtmosphereFramework.doCometSupport} hands it
     * to a handler, with {@code Accept: text/event-stream} alone and the SSE
     * interceptor inspected. The container response hands out either its
     * writer or its output stream, never both.
     */
    private Exchange exchange(String method, String body, String sessionId) throws Exception {
        var wire = new ByteArrayOutputStream();
        var nativeResponse = Mockito.mock(HttpServletResponse.class);
        var stream = new CapturingStream(wire);
        var writer = new PrintWriter(new OutputStreamWriter(wire, StandardCharsets.UTF_8), true);
        var handedOut = new String[1];
        Mockito.when(nativeResponse.getOutputStream()).thenAnswer(inv -> {
            if ("writer".equals(handedOut[0])) {
                throw new IllegalStateException("getWriter() has already been called");
            }
            handedOut[0] = "stream";
            return stream;
        });
        Mockito.when(nativeResponse.getWriter()).thenAnswer(inv -> {
            if ("stream".equals(handedOut[0])) {
                throw new IllegalStateException("getOutputStream() has already been called");
            }
            handedOut[0] = "writer";
            return writer;
        });

        var request = new AtmosphereRequestImpl.Builder()
                .method(method)
                .pathInfo("/mcp")
                .inputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                .build();
        request.header("Accept", "text/event-stream");
        if (sessionId != null) {
            request.header(McpSession.SESSION_ID_HEADER, sessionId);
        }
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
        return new Exchange(resource, wire, writer);
    }

    private record Exchange(AtmosphereResourceImpl resource, ByteArrayOutputStream wire,
                            PrintWriter nativeWriter) {
        /** The wire once the container completes the response, flushing its writer. */
        String completed() {
            nativeWriter.flush();
            return wire.toString(StandardCharsets.UTF_8);
        }
    }

    record Event(String type, String data) {
    }

    /**
     * Event-stream interpretation per the WHATWG HTML spec (9.2.6): lines end
     * at CRLF, LF or CR; a blank line dispatches the buffered data with the
     * buffered event type (default {@code message}); a {@code data} field
     * appends its value (one leading space stripped) plus LF; an {@code event}
     * field sets the type; comments and other fields contribute nothing; data
     * still buffered at end of stream is discarded.
     */
    static List<Event> strictSseEvents(String stream) {
        var events = new ArrayList<Event>();
        var data = new StringBuilder();
        String type = "";
        var lines = stream.split("\r\n|\n|\r", -1);
        // The last element follows the final line terminator: it is an
        // incomplete line and never processed.
        for (int i = 0; i < lines.length - 1; i++) {
            var line = lines[i];
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    data.setLength(data.length() - 1);
                    events.add(new Event(type.isEmpty() ? "message" : type, data.toString()));
                }
                data.setLength(0);
                type = "";
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
            } else if ("event".equals(field)) {
                type = value;
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
        public void write(int b) throws IOException {
            sink.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            sink.write(b, off, len);
        }
    }
}
