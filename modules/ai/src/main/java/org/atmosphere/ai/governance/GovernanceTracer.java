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
package org.atmosphere.ai.governance;

/**
 * Small helper that emits an OpenTelemetry span per policy evaluation when
 * {@code io.opentelemetry.api} is on the classpath and a non-noop tracer is
 * installed. Absent OTel → the helper is a no-op and the pipeline cost is
 * one {@code Class.forName} check at class-init time.
 *
 * <p>Keeps OTel usage out of the hot path in {@code AiPipeline} and
 * {@code PolicyAdmissionGate} so those files stay readable.</p>
 */
public final class GovernanceTracer {

    private static final String SPAN_NAME = "governance.policy.evaluate";

    /** Cached tracer; {@code null} when OTel is absent or unavailable. */
    private static final Object TRACER;

    static {
        Object tracer = null;
        try {
            var otelClass = Class.forName("io.opentelemetry.api.GlobalOpenTelemetry");
            var getTracer = otelClass.getMethod("getTracer", String.class);
            tracer = getTracer.invoke(null, "atmosphere.ai.governance");
        } catch (Throwable ignored) {
            // OTel not on classpath — stays null; every start() call is a no-op.
        }
        TRACER = tracer;
    }

    private GovernanceTracer() { }

    /**
     * Start a span for a policy evaluation. Returns an {@link Handle} that
     * callers {@code end()} in a finally block. When OTel is not on the
     * classpath, returns a no-op handle.
     */
    public static Handle start(GovernancePolicy policy, PolicyContext context) {
        if (TRACER == null) {
            return Handle.NOOP;
        }
        try {
            var spanBuilder = TRACER.getClass().getMethod("spanBuilder", String.class)
                    .invoke(TRACER, SPAN_NAME);
            var span = spanBuilder.getClass().getMethod("startSpan").invoke(spanBuilder);
            var setAttribute = span.getClass().getMethod("setAttribute", String.class, String.class);
            setAttribute.invoke(span, "policy.name", policy == null ? "unknown" : policy.name());
            if (policy != null) {
                setAttribute.invoke(span, "policy.source", policy.source());
                setAttribute.invoke(span, "policy.version", policy.version());
            }
            if (context != null) {
                setAttribute.invoke(span, "policy.phase",
                        context.phase() == PolicyContext.Phase.PRE_ADMISSION ? "pre_admission" : "post_response");
            }
            return new Handle(span);
        } catch (Throwable ignored) {
            return Handle.NOOP;
        }
    }

    /** Per-evaluation span handle; closed via {@link #end(String, String)}. */
    public static final class Handle {
        static final Handle NOOP = new Handle(null);
        private final Object span;

        private Handle(Object span) {
            this.span = span;
        }

        /**
         * Tag the span with the final decision + reason and end it. Safe
         * to call multiple times; only the first call records.
         */
        public void end(String decision, String reason) {
            if (span == null) return;
            try {
                var setAttribute = span.getClass().getMethod("setAttribute", String.class, String.class);
                setAttribute.invoke(span, "policy.decision", decision == null ? "" : decision);
                setAttribute.invoke(span, "policy.reason", reason == null ? "" : reason);
                if ("deny".equalsIgnoreCase(decision) || "error".equalsIgnoreCase(decision)) {
                    // Mark span as error so Jaeger/Tempo surfaces it clearly.
                    try {
                        var statusCodeClass = Class.forName("io.opentelemetry.api.trace.StatusCode");
                        var error = statusCodeClass.getField("ERROR").get(null);
                        span.getClass().getMethod("setStatus", statusCodeClass, String.class)
                                .invoke(span, error, reason == null ? "" : reason);
                    } catch (Throwable ignored) {
                        // older OTel API shape — skip status setting
                    }
                }
                span.getClass().getMethod("end").invoke(span);
            } catch (Throwable ignored) {
                // span end failure should never break the turn
            }
        }
    }
}
