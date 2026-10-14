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

import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Writes JSON-RPC payloads that a protocol handler frames itself as
 * Server-Sent Events, so they reach the wire exactly once framed.
 *
 * <p>A request whose {@code Accept} is exactly {@code text/event-stream} (an
 * EventSource, or an MCP/A2A client sending only that value) selects cpr's
 * {@code SSEAtmosphereInterceptor}, which frames everything written through the
 * {@link AtmosphereResponse}: it re-prefixes each line with {@code data:} and
 * consumes the blank-line terminators, so a WHATWG-compliant client never
 * dispatches an already-framed event. The frames are therefore written to the
 * native container response (the servlet container's, or the Quarkus Vert.x
 * container's), using the output stream or the writer as the interceptor's own
 * padding does ({@link ApplicationConfig#PROPERTY_USE_STREAM}), since a
 * container hands out only one of the two per response.</p>
 *
 * <p>When the {@link AtmosphereResponse} wraps no native response (a WebSocket
 * response, whose native side is a no-op proxy) the frames go through the
 * {@link AtmosphereResponse} itself: the SSE interceptor never frames a
 * WebSocket write, so they are still framed once.</p>
 */
public final class SseEvents {

    /** The SSE media type. */
    public static final String TEXT_EVENT_STREAM = "text/event-stream";

    private static final Logger logger = LoggerFactory.getLogger(SseEvents.class);

    private SseEvents() {
    }

    /**
     * Frame {@code data} as one {@code message} event. Each line of
     * {@code data} becomes its own {@code data:} field, so a payload that spans
     * lines still arrives as one event (a CRLF or CR line end reaches the
     * client as LF, as the SSE format defines).
     */
    public static String message(String data) {
        var event = new StringBuilder(data.length() + 24).append("event: message\n");
        for (var line : data.split("\r\n|\r|\n", -1)) {
            event.append("data: ").append(line).append('\n');
        }
        return event.append('\n').toString();
    }

    /**
     * Deliver one message on a live connection, such as a broadcast to a
     * suspended GET stream or the answer to a message received on it. An SSE
     * stream gets it as one {@code message} event, framed here and written past
     * the SSE interceptor, whether the client's {@code Accept} is exactly
     * {@code text/event-stream} or a list that includes it. Any other transport
     * (WebSocket, long-polling) gets the message unchanged.
     */
    public static void deliver(AtmosphereResource resource, String message) throws IOException {
        var response = resource.getResponse();
        if (isEventStream(resource)) {
            write(response, message(message));
            return;
        }
        var writer = response.getWriter();
        writer.write(message);
        writer.flush();
    }

    /**
     * True when {@code resource} is an SSE stream: not a WebSocket, and either
     * negotiated as SSE or asked for {@code text/event-stream}. A resource with
     * no request carries no {@code Accept} to go by and is not treated as one.
     */
    static boolean isEventStream(AtmosphereResource resource) {
        var transport = resource.transport();
        var request = resource.getRequest();
        if (request == null || transport == AtmosphereResource.TRANSPORT.WEBSOCKET
                || request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE) != null) {
            return false;
        }
        if (transport == AtmosphereResource.TRANSPORT.SSE) {
            return true;
        }
        var accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains(TEXT_EVENT_STREAM);
    }

    /**
     * Start an SSE response: status 200, {@code text/event-stream}, UTF-8, set on
     * the {@link AtmosphereResponse} and on the native response it wraps.
     */
    public static void begin(AtmosphereResponse response) {
        response.setStatus(200);
        response.setContentType(TEXT_EVENT_STREAM);
        response.setCharacterEncoding("UTF-8");
        nativeResponse(response).ifPresent(target -> {
            target.setStatus(200);
            target.setContentType(TEXT_EVENT_STREAM);
            target.setCharacterEncoding("UTF-8");
        });
    }

    /** Write already-framed SSE events unchanged and flush them to the client. */
    public static void write(AtmosphereResponse response, String events) throws IOException {
        var target = nativeResponse(response);
        if (target.isEmpty()) {
            logger.debug("No native response under {}; writing SSE events through it",
                    response.uuid());
            var writer = response.getWriter();
            writer.write(events);
            writer.flush();
            return;
        }
        if (usesStream(response)) {
            var out = target.get().getOutputStream();
            out.write(events.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } else {
            var writer = target.get().getWriter();
            writer.write(events);
            writer.flush();
        }
    }

    /**
     * The container response under {@code response}, empty when there is none:
     * {@code AtmosphereResponseImpl.getResponse()} returns the wrapper itself
     * when it wraps the no-op proxy a WebSocket response is created with.
     */
    static Optional<HttpServletResponse> nativeResponse(AtmosphereResponse response) {
        var target = response.getResponse();
        if (target == response || target instanceof Proxy
                || !(target instanceof HttpServletResponse servletResponse)) {
            return Optional.empty();
        }
        return Optional.of(servletResponse);
    }

    private static boolean usesStream(AtmosphereResponse response) {
        var request = response.request();
        var useStream = request == null ? null : request.getAttribute(ApplicationConfig.PROPERTY_USE_STREAM);
        // Same default as cpr's ResponseWriter: the stream unless told otherwise.
        return !Boolean.FALSE.equals(useStream);
    }
}
