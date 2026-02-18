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
package org.atmosphere.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenTelemetry tracing interceptor for Atmosphere requests.
 *
 * <p>Creates a trace span for each incoming request covering the full
 * lifecycle from {@code inspect} through to disconnect. Requires
 * {@code io.opentelemetry:opentelemetry-api} on the classpath (optional dependency).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * OpenTelemetry otel = GlobalOpenTelemetry.get(); // or your configured instance
 * framework.interceptor(new AtmosphereTracing(otel));
 * }</pre>
 *
 * <h3>Span attributes</h3>
 * <ul>
 *   <li>{@code atmosphere.resource.uuid} — the resource UUID</li>
 *   <li>{@code atmosphere.transport} — transport type (WEBSOCKET, SSE, etc.)</li>
 *   <li>{@code atmosphere.action} — the action result (CONTINUE, SUSPEND, etc.)</li>
 *   <li>{@code atmosphere.broadcaster} — the broadcaster ID</li>
 *   <li>{@code atmosphere.disconnect.reason} — reason for disconnect (if applicable)</li>
 * </ul>
 *
 * @since 4.0
 */
public class AtmosphereTracing extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereTracing.class);

    private static final AttributeKey<String> ATTR_UUID = AttributeKey.stringKey("atmosphere.resource.uuid");
    private static final AttributeKey<String> ATTR_TRANSPORT = AttributeKey.stringKey("atmosphere.transport");
    private static final AttributeKey<String> ATTR_ACTION = AttributeKey.stringKey("atmosphere.action");
    private static final AttributeKey<String> ATTR_BROADCASTER = AttributeKey.stringKey("atmosphere.broadcaster");
    private static final AttributeKey<String> ATTR_DISCONNECT_REASON = AttributeKey.stringKey("atmosphere.disconnect.reason");
    private static final AttributeKey<String> ATTR_ROOM = AttributeKey.stringKey("atmosphere.room");

    private static final String SPAN_KEY = AtmosphereTracing.class.getName() + ".span";
    private static final String SCOPE_KEY = AtmosphereTracing.class.getName() + ".scope";

    private final Tracer tracer;

    /**
     * Create tracing interceptor with the given OpenTelemetry instance.
     *
     * @param openTelemetry the OpenTelemetry instance
     */
    public AtmosphereTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("org.atmosphere", "4.0.0");
    }

    /**
     * Create tracing interceptor with a custom tracer.
     *
     * @param tracer the OpenTelemetry tracer
     */
    public AtmosphereTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void configure(AtmosphereConfig config) {
        logger.info("OpenTelemetry tracing enabled for Atmosphere");
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        super.inspect(r);

        String method = r.getRequest().getMethod();
        String path = r.getRequest().getPathInfo();
        if (path == null) {
            path = r.getRequest().getRequestURI();
        }

        Span span = tracer.spanBuilder("atmosphere " + method + " " + path)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(ATTR_UUID, r.uuid())
                .setAttribute(ATTR_TRANSPORT, r.transport().name())
                .startSpan();

        if (r.getBroadcaster() != null) {
            span.setAttribute(ATTR_BROADCASTER, r.getBroadcaster().getID());
        }

        Scope scope = span.makeCurrent();

        // Store span and scope on the request for retrieval in postInspect
        r.getRequest().setAttribute(SPAN_KEY, span);
        r.getRequest().setAttribute(SCOPE_KEY, scope);

        // Add lifecycle listener for long-lived connections
        r.addEventListener(new TracingResourceListener(span));

        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource r) {
        Span span = (Span) r.getRequest().getAttribute(SPAN_KEY);
        if (span != null) {
            String action = r.getAtmosphereResourceEvent() != null && r.getAtmosphereResourceEvent().isSuspended()
                    ? "SUSPEND" : "CONTINUE";
            span.setAttribute(ATTR_ACTION, action);

            // For non-suspended requests, end the span now
            if (!"SUSPEND".equals(action)) {
                endSpan(r);
            }
            // Suspended requests will be ended by the lifecycle listener
        }
    }

    private void endSpan(AtmosphereResource r) {
        Scope scope = (Scope) r.getRequest().getAttribute(SCOPE_KEY);
        Span span = (Span) r.getRequest().getAttribute(SPAN_KEY);

        if (scope != null) {
            scope.close();
            r.getRequest().removeAttribute(SCOPE_KEY);
        }
        if (span != null) {
            span.end();
            r.getRequest().removeAttribute(SPAN_KEY);
        }
    }

    /**
     * Create a span for a room operation (join, leave, broadcast).
     *
     * @param operation the operation name (e.g., "join", "leave", "broadcast")
     * @param roomName  the room name
     * @param uuid      the resource UUID
     * @return the span (caller must call {@code span.end()})
     */
    public Span startRoomSpan(String operation, String roomName, String uuid) {
        return tracer.spanBuilder("atmosphere.room." + operation)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(ATTR_ROOM, roomName)
                .setAttribute(ATTR_UUID, uuid)
                .startSpan();
    }

    /**
     * Tracks resource lifecycle events and adds them to the trace span.
     */
    private static class TracingResourceListener extends AtmosphereResourceEventListenerAdapter {

        private final Span span;

        TracingResourceListener(Span span) {
            this.span = span;
        }

        @Override
        public void onSuspend(AtmosphereResourceEvent event) {
            span.addEvent("atmosphere.suspend");
        }

        @Override
        public void onResume(AtmosphereResourceEvent event) {
            span.addEvent("atmosphere.resume");
            if (event.isResumedOnTimeout()) {
                span.setAttribute(ATTR_DISCONNECT_REASON, "timeout");
            }
            endIfActive();
        }

        @Override
        public void onBroadcast(AtmosphereResourceEvent event) {
            span.addEvent("atmosphere.broadcast", Attributes.of(
                    AttributeKey.stringKey("atmosphere.message.type"),
                    event.getMessage() != null ? event.getMessage().getClass().getSimpleName() : "null"
            ));
        }

        @Override
        public void onDisconnect(AtmosphereResourceEvent event) {
            String reason = event.isClosedByClient() ? "client" : "application";
            span.setAttribute(ATTR_DISCONNECT_REASON, reason);
            span.addEvent("atmosphere.disconnect");
            endIfActive();
        }

        @Override
        public void onClose(AtmosphereResourceEvent event) {
            span.addEvent("atmosphere.close");
            endIfActive();
        }

        @Override
        public void onThrowable(AtmosphereResourceEvent event) {
            Throwable t = event.throwable();
            if (t != null) {
                span.recordException(t);
                span.setStatus(StatusCode.ERROR, t.getMessage());
            }
            endIfActive();
        }

        private void endIfActive() {
            span.end();
        }
    }

    @Override
    public String toString() {
        return "AtmosphereTracing{opentelemetry}";
    }
}
