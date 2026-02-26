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
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * OpenTelemetry tracing for MCP tool/resource/prompt calls.
 *
 * <p>Creates a trace span per tool invocation with attributes for tool name,
 * argument count, success/error status, and duration. Requires
 * {@code io.opentelemetry:opentelemetry-api} on the classpath (optional dependency).</p>
 *
 * <h3>Span attributes</h3>
 * <ul>
 *   <li>{@code mcp.tool.name} — the tool/resource/prompt name</li>
 *   <li>{@code mcp.tool.type} — "tool", "resource", or "prompt"</li>
 *   <li>{@code mcp.tool.arg_count} — number of arguments provided</li>
 *   <li>{@code mcp.tool.error} — true if the invocation failed</li>
 * </ul>
 *
 * @since 4.0.5
 */
public final class McpTracing {

    public static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("mcp.tool.name");
    public static final AttributeKey<String> TOOL_TYPE = AttributeKey.stringKey("mcp.tool.type");
    public static final AttributeKey<Long> ARG_COUNT = AttributeKey.longKey("mcp.tool.arg_count");
    public static final AttributeKey<Boolean> TOOL_ERROR = AttributeKey.booleanKey("mcp.tool.error");

    private final Tracer tracer;

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
}
