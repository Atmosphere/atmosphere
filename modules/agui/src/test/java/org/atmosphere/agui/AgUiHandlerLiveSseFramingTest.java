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
package org.atmosphere.agui;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.agui.annotation.AgUiAction;
import org.atmosphere.agui.runtime.AgUiHandler;
import org.atmosphere.agui.runtime.RunContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

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

/**
 * A message delivered to a suspended AG-UI GET stream must reach the client
 * as one spec-valid SSE event whether {@code Accept} is exactly
 * {@code text/event-stream} (which selects cpr's
 * {@link SSEAtmosphereInterceptor}) or a list that includes it, and must stay
 * unchanged on a WebSocket.
 */
class AgUiHandlerLiveSseFramingTest {

    private AtmosphereFramework framework;
    private AgUiHandler handler;

    public static class Endpoint {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.complete("Done");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(Mockito.mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "agui-sse";
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
        handler = new AgUiHandler(new Endpoint(), Endpoint.class.getMethod("onRun",
                RunContext.class, StreamingSession.class));
    }

    @AfterEach
    void tearDown() {
        handler.destroy();
        framework.destroy();
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/event-stream", "application/json, text/event-stream"})
    void messageOnTheGetStreamIsOneEventForEitherAccept(String accept) throws Exception {
        var wire = new ByteArrayOutputStream();
        var nativeResponse = Mockito.mock(HttpServletResponse.class);
        Mockito.when(nativeResponse.getOutputStream()).thenReturn(new CapturingStream(wire));
        var nativeWriter = new PrintWriter(new OutputStreamWriter(wire, StandardCharsets.UTF_8), true);
        Mockito.when(nativeResponse.getWriter()).thenReturn(nativeWriter);

        var request = new AtmosphereRequestImpl.Builder()
                .method("GET")
                .pathInfo("/agui")
                .inputStream(new ByteArrayInputStream(new byte[0]))
                .build();
        request.header("Accept", accept);
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
        handler.onStateChange(new AtmosphereResourceEventImpl(resource)
                .setMessage("{\"type\":\"CUSTOM\",\"name\":\"n\",\"value\":1}"));

        // A container flushes its writer when the response completes.
        nativeWriter.flush();
        var stream = wire.toString(StandardCharsets.UTF_8);
        assertEquals(List.of(new Event("message", "{\"type\":\"CUSTOM\",\"name\":\"n\",\"value\":1}")),
                strictSseEvents(stream), "wire was: " + stream.strip());
    }

    @Test
    void messageOnAWebSocketStaysUnchanged() throws Exception {
        var request = new AtmosphereRequestImpl.Builder()
                .method("GET")
                .pathInfo("/agui")
                .inputStream(new ByteArrayInputStream(new byte[0]))
                .build();
        request.header(HeaderConfig.X_ATMOSPHERE_TRANSPORT, "websocket");
        var frames = new ByteArrayOutputStream();
        // As a WebSocket response: no status line, writes go to the socket.
        var response = new AtmosphereResponseImpl.Builder()
                .request(request)
                .writeHeader(false)
                .asyncIOWriter(new AsyncIOWriterAdapter() {
                    @Override
                    public AsyncIOWriter write(AtmosphereResponse r, String data) {
                        frames.writeBytes(data.getBytes(StandardCharsets.UTF_8));
                        return this;
                    }

                    @Override
                    public AsyncIOWriter write(AtmosphereResponse r, byte[] data) {
                        frames.writeBytes(data);
                        return this;
                    }

                    @Override
                    public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) {
                        frames.write(data, offset, length);
                        return this;
                    }
                })
                .build();
        var resource = new AtmosphereResourceImpl();
        resource.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, Mockito.mock(AsyncSupport.class), handler);
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);

        handler.onStateChange(new AtmosphereResourceEventImpl(resource).setMessage("{\"ws\":1}"));

        assertEquals("{\"ws\":1}", frames.toString(StandardCharsets.UTF_8));
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
