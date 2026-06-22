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
package org.atmosphere.ai;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reflection-based emitter for the <em>experimental</em>
 * <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/">OpenTelemetry
 * GenAI semantic-convention</a> span attributes. When
 * {@code io.opentelemetry.api} is on the classpath and a live span is active
 * (an {@code AtmosphereTracing} SERVER span, for example), {@link #record}
 * tags the <em>current</em> span with the {@code gen_ai.*} attributes so the
 * same token counts Atmosphere already publishes through {@code ai.tokens.*}
 * and the {@code gen_ai.client.token.usage} metric also hang off the trace.
 *
 * <p>This is purely additive. The legacy {@code ai.tokens.*} metadata and the
 * {@code atmosphere.ai.*} / {@code gen_ai.client.*} Micrometer series are
 * untouched. Absent OTel, or absent a current span, this class is a no-op and
 * the pipeline cost is one {@code Class.forName} check at class-init time.</p>
 *
 * <p>Mirrors {@link org.atmosphere.ai.governance.GovernanceTracer}: no hard
 * OpenTelemetry main dependency, the reflection handles are cached at
 * class-init, and every failure path is swallowed so observability can never
 * break the turn.</p>
 *
 * <p><strong>Runtime Truth.</strong> {@code gen_ai.response.model} is emitted
 * only when the runtime actually reported a model on the {@link TokenUsage}
 * record; it is never substituted with a placeholder. {@code gen_ai.provider.name}
 * carries the resolved {@code AgentRuntime.name()}, never a hardcoded value.</p>
 */
public final class GenAiTracer {

    // --- GenAI semconv attribute keys (experimental). ---
    static final String ATTR_USAGE_INPUT = "gen_ai.usage.input_tokens";
    static final String ATTR_USAGE_OUTPUT = "gen_ai.usage.output_tokens";
    static final String ATTR_USAGE_TOTAL = "gen_ai.usage.total_tokens";
    static final String ATTR_REQUEST_MODEL = "gen_ai.request.model";
    static final String ATTR_RESPONSE_MODEL = "gen_ai.response.model";
    static final String ATTR_OPERATION_NAME = "gen_ai.operation.name";
    static final String ATTR_PROVIDER_NAME = "gen_ai.provider.name";

    /** GenAI {@code gen_ai.operation.name} value for chat completions. */
    static final String OPERATION_CHAT = "chat";

    /** {@code Span.current()} — cached; {@code null} when OTel is absent. */
    private static final MethodHandle SPAN_CURRENT;
    /** {@code Span.getSpanContext()} — cached. */
    private static final MethodHandle SPAN_GET_CONTEXT;
    /** {@code SpanContext.isValid()} — cached. */
    private static final MethodHandle CONTEXT_IS_VALID;
    /** {@code Span.setAttribute(String, String)} — cached. */
    private static final MethodHandle SET_ATTR_STRING;
    /** {@code Span.setAttribute(String, long)} — cached. */
    private static final MethodHandle SET_ATTR_LONG;

    static {
        MethodHandle current = null;
        MethodHandle getContext = null;
        MethodHandle isValid = null;
        MethodHandle setString = null;
        MethodHandle setLong = null;
        try {
            var lookup = MethodHandles.publicLookup();
            var spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            var contextClass = Class.forName("io.opentelemetry.api.trace.SpanContext");
            current = lookup.findStatic(spanClass, "current", MethodType.methodType(spanClass));
            getContext = lookup.findVirtual(spanClass, "getSpanContext",
                    MethodType.methodType(contextClass));
            isValid = lookup.findVirtual(contextClass, "isValid",
                    MethodType.methodType(boolean.class));
            setString = lookup.findVirtual(spanClass, "setAttribute",
                    MethodType.methodType(spanClass, String.class, String.class));
            setLong = lookup.findVirtual(spanClass, "setAttribute",
                    MethodType.methodType(spanClass, String.class, long.class));
        } catch (Throwable ignored) {
            // OTel not on classpath (or an incompatible API shape). The locals
            // keep their null initializers, so every record() call becomes a
            // no-op (gated on SPAN_CURRENT == null). Nothing is logged at
            // class-init, matching GovernanceTracer.
        }
        SPAN_CURRENT = current;
        SPAN_GET_CONTEXT = getContext;
        CONTEXT_IS_VALID = isValid;
        SET_ATTR_STRING = setString;
        SET_ATTR_LONG = setLong;
    }

    private GenAiTracer() { }

    /**
     * Tag the current OpenTelemetry span with the experimental GenAI
     * semantic-convention attributes for one chat completion. Sets, on the
     * <em>active</em> span (never a freshly-created one):
     *
     * <ul>
     *   <li>{@code gen_ai.usage.input_tokens} (long)</li>
     *   <li>{@code gen_ai.usage.output_tokens} (long)</li>
     *   <li>{@code gen_ai.usage.total_tokens} (long)</li>
     *   <li>{@code gen_ai.request.model}</li>
     *   <li>{@code gen_ai.response.model} &mdash; only when {@code usage.model()}
     *       is non-blank (Runtime Truth: no placeholder)</li>
     *   <li>{@code gen_ai.operation.name} = {@code "chat"}</li>
     *   <li>{@code gen_ai.provider.name} = the resolved runtime name</li>
     * </ul>
     *
     * <p>Skips entirely when OTel is absent, when {@code usage} is {@code null}
     * or {@link TokenUsage#hasCounts()} is {@code false}, or when there is no
     * valid current span (so it never orphans a new span onto the trace).</p>
     *
     * @param usage         the provider-reported token counts; ignored when
     *                      {@code null} or carrying no counts
     * @param requestModel  the model the request targeted (may be {@code null})
     * @param providerName  the resolved {@code AgentRuntime.name()} (Runtime
     *                      Truth — never a hardcoded "atmosphere")
     */
    public static void record(TokenUsage usage, String requestModel, String providerName) {
        // All-or-nothing: a partially-resolved API (e.g. an incompatible OTel
        // shape) leaves some handles null. Gate on every handle so record()
        // is a clean no-op rather than relying on the catch below.
        if (SPAN_CURRENT == null || SPAN_GET_CONTEXT == null || CONTEXT_IS_VALID == null
                || SET_ATTR_STRING == null || SET_ATTR_LONG == null
                || usage == null || !usage.hasCounts()) {
            return;
        }
        try {
            var span = SPAN_CURRENT.invoke();
            if (span == null) {
                return;
            }
            var context = SPAN_GET_CONTEXT.invoke(span);
            // No valid span context means Span.current() returned the invalid
            // no-op span (no AtmosphereTracing SERVER span on this thread).
            // Setting attributes there would be silently dropped, so skip —
            // and critically, never create a span to hold them (no orphan).
            if (context == null || !(boolean) CONTEXT_IS_VALID.invoke(context)) {
                return;
            }
            SET_ATTR_LONG.invoke(span, ATTR_USAGE_INPUT, usage.input());
            SET_ATTR_LONG.invoke(span, ATTR_USAGE_OUTPUT, usage.output());
            SET_ATTR_LONG.invoke(span, ATTR_USAGE_TOTAL, usage.total());
            SET_ATTR_STRING.invoke(span, ATTR_OPERATION_NAME, OPERATION_CHAT);
            SET_ATTR_STRING.invoke(span, ATTR_PROVIDER_NAME,
                    providerName != null && !providerName.isBlank() ? providerName : "unknown");
            SET_ATTR_STRING.invoke(span, ATTR_REQUEST_MODEL,
                    requestModel != null && !requestModel.isBlank() ? requestModel : "unknown");
            // Runtime Truth: only advertise a response model the runtime
            // actually reported. Omit the attribute otherwise — no placeholder.
            var responseModel = usage.model();
            if (responseModel != null && !responseModel.isBlank()) {
                SET_ATTR_STRING.invoke(span, ATTR_RESPONSE_MODEL, responseModel);
            }
        } catch (Throwable ignored) {
            // Span tagging must never break the turn; the legacy ai.tokens.*
            // and atmosphere.ai.* signals already carried the same data.
        }
    }
}
