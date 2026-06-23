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

import java.util.HashMap;
import java.util.Locale;

/**
 * Cross-runtime plumbing for provider-native structured output
 * ({@link AiCapability#NATIVE_STRUCTURED_OUTPUT}).
 *
 * <p>The pipeline decides <em>whether</em> to enforce a schema natively (based on
 * {@link NativeStructuredOutputMode} and the resolved runtime's capabilities) and
 * stamps two request-metadata keys via {@link #withApply}; each native-capable
 * runtime then reads {@link #shouldApply(AgentExecutionContext)} +
 * {@link #schema(AgentExecutionContext)} at the point it builds its provider
 * request and threads the schema into the provider's native field. Keeping the
 * decision in one place (the pipeline) and the application uniform (this helper)
 * means the nine runtimes don't each re-implement mode resolution or schema
 * generation — they only know their own SDK's schema-setter.</p>
 *
 * <p>{@link NativeStructuredDispatch} owns the {@link NativeStructuredOutputMode#AUTO}
 * graceful fall-back: on a provider schema rejection it re-dispatches with the
 * apply flag cleared (via {@link #withoutApply}) so the runtime drops back to the
 * prompt-injection path. {@link #isSchemaRejection(Throwable)} is the shared
 * heuristic for "the provider refused the schema" vs. an unrelated failure.</p>
 */
public final class NativeStructuredOutput {

    /** Request-metadata key (Boolean): apply the provider-native schema this dispatch. */
    public static final String APPLY_METADATA_KEY = "ai.native-structured.apply";

    /** Request-metadata key (String): the raw JSON Schema to thread into the provider field. */
    public static final String SCHEMA_METADATA_KEY = "ai.native-structured.schema";

    private NativeStructuredOutput() {
    }

    /**
     * @return {@code true} when the pipeline asked this dispatch to apply the
     *         provider-native schema. Runtimes gate their native-schema-setter on
     *         this AND a non-null {@link #schema(AgentExecutionContext)}.
     */
    public static boolean shouldApply(AgentExecutionContext context) {
        return context != null && context.metadata().get(APPLY_METADATA_KEY) == Boolean.TRUE;
    }

    /**
     * @return the raw JSON Schema string the pipeline stamped for this request, or
     *         {@code null} when none is available (runtime stays on prompt-injection).
     */
    public static String schema(AgentExecutionContext context) {
        if (context == null) {
            return null;
        }
        return context.metadata().get(SCHEMA_METADATA_KEY) instanceof String s && !s.isBlank()
                ? s : null;
    }

    /**
     * Render the raw JSON Schema for a response type via the resolved
     * {@link StructuredOutputParser}. Returns {@code null} when the parser cannot
     * produce a machine-readable schema (the signal to skip native enforcement).
     */
    public static String schemaFor(Class<?> responseType) {
        if (responseType == null || responseType == Void.class) {
            return null;
        }
        return StructuredOutputParser.resolve().jsonSchema(responseType);
    }

    /**
     * Return a context carrying the apply flag and schema, merged onto the
     * existing metadata (so cache hints, budgets, retry config etc. survive).
     */
    public static AgentExecutionContext withApply(AgentExecutionContext context, String schema) {
        var merged = new HashMap<>(context.metadata());
        merged.put(APPLY_METADATA_KEY, Boolean.TRUE);
        merged.put(SCHEMA_METADATA_KEY, schema);
        return context.withMetadata(merged);
    }

    /**
     * Return a context with the apply flag cleared — used by the graceful
     * fall-back to re-dispatch on the prompt-injection path.
     */
    public static AgentExecutionContext withoutApply(AgentExecutionContext context) {
        var merged = new HashMap<>(context.metadata());
        merged.put(APPLY_METADATA_KEY, Boolean.FALSE);
        merged.remove(SCHEMA_METADATA_KEY);
        return context.withMetadata(merged);
    }

    /**
     * Heuristic: does this failure look like the provider rejecting the schema we
     * sent (as opposed to a rate-limit, auth, network, or downstream error)?
     * Deliberately conservative — it only matches when the message references a
     * schema / response-format construct, so an unrelated 400 does not trigger a
     * pointless fall-back re-dispatch. Walks the cause chain.
     */
    public static boolean isSchemaRejection(Throwable t) {
        for (var cause = t; cause != null; cause = cause.getCause()) {
            var message = cause.getMessage();
            if (message != null) {
                var m = message.toLowerCase(Locale.ROOT);
                if (m.contains("response_format")
                        || m.contains("json_schema")
                        || m.contains("response schema")
                        || m.contains("responseschema")
                        || m.contains("output_config")
                        || m.contains("invalid schema")
                        || m.contains("schema is invalid")
                        || m.contains("unsupported schema")
                        || (m.contains("schema") && m.contains("not supported"))
                        || (m.contains("schema") && m.contains("structured output"))) {
                    return true;
                }
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }
}
