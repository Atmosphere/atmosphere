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
package org.atmosphere.protocol;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AbstractProtocolHandler#writeResponse} and
 * {@link AbstractProtocolHandler#replayPending} frame their own SSE events.
 * A request whose {@code Accept} is exactly {@code text/event-stream} (an
 * EventSource, or an MCP/A2A client sending only that value) also selects
 * cpr's {@link SSEAtmosphereInterceptor}; the frames must still reach the wire
 * unchanged, so a WHATWG event-stream parser dispatches exactly one
 * {@code message} event per JSON-RPC payload.
 */
class AbstractProtocolHandlerSseFramingTest {

    private AtmosphereFramework framework;
    private TestHandler handler;
    private PrintWriter nativeWriter;

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(Mockito.mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "protocol-sse";
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
        handler = new TestHandler();
    }

    @AfterEach
    void tearDown() {
        handler.destroy();
        framework.destroy();
    }

    @ParameterizedTest(name = "useStream={0}")
    @ValueSource(booleans = {true, false})
    void unaryResponseIsOneSpecValidEventThroughTheSseInterceptor(boolean useStream)
            throws Exception {
        var wire = new ByteArrayOutputStream();
        var resource = sseResource("POST", "text/event-stream", useStream, wire);

        handler.writeResponse(resource, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");

        var bytes = completed(wire);
        assertEquals(List.of(new Event("message", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")),
                strictSseEvents(bytes), "wire was: " + bytes.strip());
    }

    @ParameterizedTest(name = "useStream={0}")
    @ValueSource(booleans = {true, false})
    void replayedNotificationsAreSpecValidEventsOnASuspendedStream(boolean useStream)
            throws Exception {
        var wire = new ByteArrayOutputStream();
        var resource = sseResource("GET", "text/event-stream", useStream, wire);
        var session = new ProtocolSession();
        session.addPendingNotification("{\"n\":1}");
        session.addPendingNotification("{\"n\":2}");

        // What a subclass's handleGet does: suspend, then replay.
        resource.suspend();
        handler.replayPending(session, resource.getResponse());

        var bytes = completed(wire);
        assertEquals(List.of(new Event("message", "{\"n\":1}"), new Event("message", "{\"n\":2}")),
                strictSseEvents(bytes), "wire was: " + bytes.strip());
    }

    @ParameterizedTest(name = "useStream={0}")
    @ValueSource(booleans = {true, false})
    void combinedAcceptHeaderStillGetsOneEvent(boolean useStream) throws Exception {
        var wire = new ByteArrayOutputStream();
        var resource = sseResource("POST", "application/json, text/event-stream", useStream, wire);

        handler.writeResponse(resource, "{\"id\":2}");

        var bytes = completed(wire);
        assertEquals(List.of(new Event("message", "{\"id\":2}")),
                strictSseEvents(bytes), "wire was: " + bytes.strip());
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/event-stream", "application/json, text/event-stream"})
    void liveMessageOnASuspendedStreamIsOneEventForEitherAccept(String accept) throws Exception {
        var wire = new ByteArrayOutputStream();
        var resource = sseResource("GET", accept, true, wire);
        resource.suspend();

        handler.onStateChange(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) resource)
                .setMessage("{\"live\":1}"));

        var bytes = completed(wire);
        assertEquals(List.of(new Event("message", "{\"live\":1}")),
                strictSseEvents(bytes), "wire was: " + bytes.strip());
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/event-stream", "application/json, text/event-stream"})
    void liveMessageBatchOnASuspendedStreamIsOneEventPerMessage(String accept) throws Exception {
        var wire = new ByteArrayOutputStream();
        var resource = sseResource("GET", accept, true, wire);
        resource.suspend();

        handler.onStateChange(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) resource)
                .setMessage(List.of("{\"a\":1}", "{\"b\":2}")));

        var bytes = completed(wire);
        assertEquals(List.of(new Event("message", "{\"a\":1}"), new Event("message", "{\"b\":2}")),
                strictSseEvents(bytes), "wire was: " + bytes.strip());
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/event-stream", "application/json, text/event-stream"})
    void multiLineLiveMessageIsStillOneEvent(String accept) throws Exception {
        var wire = new ByteArrayOutputStream();
        var resource = sseResource("GET", accept, true, wire);
        resource.suspend();

        handler.onStateChange(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) resource)
                .setMessage("{\n  \"pretty\": true\n}"));

        var bytes = completed(wire);
        assertEquals(List.of(new Event("message", "{\n  \"pretty\": true\n}")),
                strictSseEvents(bytes), "wire was: " + bytes.strip());
    }

    @Test
    void liveMessageOnAWebSocketStaysUnframedJson() throws Exception {
        var request = new AtmosphereRequestImpl.Builder()
                .method("GET")
                .pathInfo("/protocol")
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
                    public AsyncIOWriter write(AtmosphereResponse r, byte[] data) {
                        frames.writeBytes(data);
                        return this;
                    }

                    @Override
                    public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) {
                        frames.write(data, offset, length);
                        return this;
                    }

                    @Override
                    public AsyncIOWriter write(AtmosphereResponse r, String data) {
                        frames.writeBytes(data.getBytes(StandardCharsets.UTF_8));
                        return this;
                    }
                })
                .build();
        var resource = new AtmosphereResourceImpl();
        resource.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, Mockito.mock(AsyncSupport.class), handler);
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);
        assertEquals(AtmosphereResource.TRANSPORT.WEBSOCKET, resource.transport());

        handler.onStateChange(new AtmosphereResourceEventImpl(resource).setMessage("{\"ws\":1}"));

        assertEquals("{\"ws\":1}", frames.toString(StandardCharsets.UTF_8));
    }

    /**
     * An HTTP resource as {@code AtmosphereFramework.doCometSupport} hands it
     * to a handler: wrapping a container response, with the SSE interceptor
     * already inspected. The container response enforces the servlet rule that
     * a response hands out either its writer or its output stream, never both.
     */
    private AtmosphereResource sseResource(String method, String accept, boolean useStream,
                                           ByteArrayOutputStream wire) throws Exception {
        var nativeResponse = nativeResponse(wire);
        var request = new AtmosphereRequestImpl.Builder()
                .method(method)
                .pathInfo("/protocol")
                .inputStream(new ByteArrayInputStream(new byte[0]))
                .build();
        request.header("Accept", accept);
        request.setAttribute(ApplicationConfig.PROPERTY_USE_STREAM, useStream);
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
        return resource;
    }

    private HttpServletResponse nativeResponse(ByteArrayOutputStream wire) throws IOException {
        var nativeResponse = Mockito.mock(HttpServletResponse.class);
        var handedOut = new AtomicReference<String>();
        var stream = new CapturingStream(wire);
        var writer = new PrintWriter(new OutputStreamWriter(wire, StandardCharsets.UTF_8), true);
        nativeWriter = writer;
        Mockito.when(nativeResponse.getOutputStream()).thenAnswer(inv -> {
            if ("writer".equals(handedOut.compareAndExchange(null, "stream"))) {
                throw new IllegalStateException("getWriter() has already been called");
            }
            return stream;
        });
        Mockito.when(nativeResponse.getWriter()).thenAnswer(inv -> {
            if ("stream".equals(handedOut.compareAndExchange(null, "writer"))) {
                throw new IllegalStateException("getOutputStream() has already been called");
            }
            return writer;
        });
        return nativeResponse;
    }

    /** The wire once the container completes the response, flushing its writer. */
    private String completed(ByteArrayOutputStream wire) {
        nativeWriter.flush();
        return wire.toString(StandardCharsets.UTF_8);
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

    static final class TestHandler extends AbstractProtocolHandler<ProtocolSession> {
        TestHandler() {
            super(60_000L, "X-Test-Session", "test.session", "test-sse-cleaner");
        }

        @Override
        protected void handlePost(AtmosphereResource resource) {
        }

        @Override
        protected void handleGet(AtmosphereResource resource) {
        }

        @Override
        protected void handleDelete(AtmosphereResource resource) {
        }
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
