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
package org.atmosphere.mcp.runtime;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.util.Map;

/**
 * OpenTelemetry tracing for MCP tool/resource/prompt calls. Creates a span per
 * invocation with tool name, type, argument count, and error status attributes.
 * Requires {@code io.opentelemetry:opentelemetry-api} on the classpath.
 *
 * @since 4.0.5
 */
public final class McpTracing {

    public static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("mcp.tool.name");
    public static final AttributeKey<String> TOOL_TYPE = AttributeKey.stringKey("mcp.tool.type");
    public static final AttributeKey<Long> ARG_COUNT = AttributeKey.longKey("mcp.tool.arg_count");
    public static final AttributeKey<Boolean> TOOL_ERROR = AttributeKey.booleanKey("mcp.tool.error");

    /** Reads propagation keys out of an MCP {@code _meta} trace carrier. */
    private static final TextMapGetter<Map<String, String>> CARRIER_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier == null ? null : carrier.get(key);
                }
            };

    /**
     * An {@link AutoCloseable} whose {@code close()} does not throw a checked
     * exception, so a dialect can scope a remote trace with try-with-resources
     * <em>without</em> importing any {@code io.opentelemetry.*} type (OTel is an
     * optional dependency — keeping its types confined to this class avoids a
     * {@code NoClassDefFoundError} on the OTel-absent path).
     */
    public interface TraceScope extends AutoCloseable {
        @Override
        void close();
    }

    private final Tracer tracer;

    // W3C Trace Context + Baggage propagators (SEP-414). The spec mandates W3C,
    // so we use it unconditionally rather than the host's configured propagator,
    // which may be a no-op.
    private final TextMapPropagator propagator = TextMapPropagator.composite(
            W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());

    public McpTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("atmosphere-mcp", "4.0.5");
    }

    public McpTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * A supplier that can throw checked exceptions.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Execute a tool/resource/prompt call wrapped in a trace span.
     *
     * @param <T>      the result type
     * @param type     "tool", "resource", or "prompt"
     * @param name     the tool/resource/prompt name
     * @param argCount number of arguments
     * @param action   the action to execute
     * @return the result of the action
     * @throws Exception if the action throws
     */
    @SuppressWarnings("try")
    public <T> T traced(String type, String name, int argCount,
                         ThrowingSupplier<T> action) throws Exception {
        var span = tracer.spanBuilder("mcp." + type + "/" + name)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(TOOL_NAME, name)
                .setAttribute(TOOL_TYPE, type)
                .setAttribute(ARG_COUNT, (long) argCount)
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            var result = action.get();
            span.setAttribute(TOOL_ERROR, false);
            return result;
        } catch (Exception e) {
            span.setAttribute(TOOL_ERROR, true);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Make the W3C trace context carried in an MCP request's {@code _meta}
     * (SEP-414) the current OpenTelemetry context, so spans created during
     * dispatch parent off the caller's distributed trace and baggage flows
     * downstream. The returned {@link TraceScope} MUST be closed (use
     * try-with-resources) to restore the previous context; a no-op scope is
     * returned when the carrier has no trace context.
     *
     * @param carrier the {@code traceparent}/{@code tracestate}/{@code baggage}
     *                keys extracted from {@code _meta} (may be empty)
     * @return a scope to close when dispatch completes
     */
    public TraceScope withRemoteContext(Map<String, String> carrier) {
        if (carrier == null || carrier.isEmpty()) {
            return () -> { };
        }
        var extracted = propagator.extract(Context.current(), carrier, CARRIER_GETTER);
        Scope scope = extracted.makeCurrent();
        return scope::close;
    }
}
