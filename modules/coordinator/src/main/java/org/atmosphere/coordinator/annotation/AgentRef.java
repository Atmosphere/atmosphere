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
package org.atmosphere.coordinator.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reference to an agent within a {@link Fleet}. Exactly one of {@code value()}
 * (name-based) or {@code type()} (class-based) must be specified.
 *
 * <p>Class-based references are compile-safe and provide IDE navigation:</p>
 * <pre>{@code
 * @AgentRef(type = ResearchAgent.class)
 * }</pre>
 *
 * <p>Name-based references work for remote agents or cross-module references:</p>
 * <pre>{@code
 * @AgentRef(value = "finance", version = "2.0.0")
 * }</pre>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentRef {

    /**
     * Agent name — for remote agents or when class reference isn't available.
     * Must match {@code @Agent(name=...)} or {@code @Coordinator(name=...)}.
     */
    String value() default "";

    /**
     * Agent class — for local agents. Compile-safe: the processor reads
     * {@code @Agent(name=...)} or {@code @Coordinator(name=...)} from the class
     * to resolve the name. Exactly one of {@code value()} or {@code type()}
     * must be specified.
     */
    Class<?> type() default void.class;

    /** Expected agent version. Advisory — logged and warned at startup, not enforced. */
    String version() default "";

    /** If false, coordinator starts even if this agent is unavailable. */
    boolean required() default true;

    /**
     * Preference weight for routing decisions. Higher values indicate stronger
     * preference. Reserved for future load-balancing and preference scoring
     * across agents with overlapping capabilities. Currently logged at startup
     * but not used for routing.
     */
    int weight() default 1;

    /**
     * Maximum retry attempts for transient failures. Default 0 means no retry.
     * Uses exponential backoff starting at 100ms (100ms, 200ms, 400ms, ...).
     */
    int maxRetries() default 0;

    /**
     * Enable circuit breaker protection for this agent. When true, the proxy
     * is wrapped with {@code ResilientAgentProxy} that fast-fails when the
     * circuit is open after repeated failures.
     */
    boolean circuitBreaker() default false;

    /**
     * Per-agent call timeout in milliseconds. Default 0 means "use fleet default"
     * (120 seconds). Overrides the global parallel timeout for this specific agent.
     *
     * <pre>{@code
     * @AgentRef(value = "research", timeoutMs = 30000)  // 30s timeout
     * @AgentRef(value = "writer", timeoutMs = 60000)    // 60s timeout
     * }</pre>
     */
    long timeoutMs() default 0;
}
