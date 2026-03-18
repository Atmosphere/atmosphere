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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Reusable OpenTelemetry tracing for protocol operations. Parameterized by
 * instrumentation scope name, version, and attribute prefix so that each protocol
 * (MCP, A2A, AG-UI) gets its own trace namespace.
 *
 * @since 4.0.8
 */
public final class ProtocolTracing {

    private final Tracer tracer;
    private final String prefix;
    private final AttributeKey<String> nameKey;
    private final AttributeKey<String> typeKey;
    private final AttributeKey<Long> argCountKey;
    private final AttributeKey<Boolean> errorKey;

    /**
     * @param openTelemetry the OTel instance
     * @param scopeName     instrumentation scope (e.g., "atmosphere-mcp")
     * @param scopeVersion  instrumentation version (e.g., "4.0.8")
     * @param attrPrefix    attribute prefix (e.g., "mcp" produces "mcp.tool.name")
     */
    public ProtocolTracing(OpenTelemetry openTelemetry, String scopeName,
                           String scopeVersion, String attrPrefix) {
        this(openTelemetry.getTracer(scopeName, scopeVersion), attrPrefix);
    }

    /**
     * @param tracer     a pre-built tracer
     * @param attrPrefix attribute prefix (e.g., "a2a" produces "a2a.tool.name")
     */
    public ProtocolTracing(Tracer tracer, String attrPrefix) {
        this.tracer = tracer;
        this.prefix = attrPrefix;
        this.nameKey = AttributeKey.stringKey(attrPrefix + ".tool.name");
        this.typeKey = AttributeKey.stringKey(attrPrefix + ".tool.type");
        this.argCountKey = AttributeKey.longKey(attrPrefix + ".tool.arg_count");
        this.errorKey = AttributeKey.booleanKey(attrPrefix + ".tool.error");
    }

    /**
     * A supplier that can throw checked exceptions.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Execute an operation wrapped in a trace span.
     *
     * @param <T>      the result type
     * @param type     the operation type (e.g., "tool", "resource", "skill")
     * @param name     the operation name
     * @param argCount number of arguments
     * @param action   the action to execute
     * @return the result of the action
     * @throws Exception if the action throws
     */
    @SuppressWarnings("try")
    public <T> T traced(String type, String name, int argCount,
                         ThrowingSupplier<T> action) throws Exception {
        var span = tracer.spanBuilder(prefix + "." + type + "/" + name)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(nameKey, name)
                .setAttribute(typeKey, type)
                .setAttribute(argCountKey, (long) argCount)
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            var result = action.get();
            span.setAttribute(errorKey, false);
            return result;
        } catch (Exception e) {
            span.setAttribute(errorKey, true);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** Returns the attribute prefix. */
    public String prefix() {
        return prefix;
    }
}
