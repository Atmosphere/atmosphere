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
package org.atmosphere.ai.facts;

import java.util.Map;
import java.util.Optional;

/**
 * Deterministic-fact resolver. Named primitive for injecting grounded,
 * verifiable facts (user profile, locale, time, feature flags, recent
 * user events) into every agent turn so generative decisions anchor to
 * real-world state rather than model memory.
 *
 * <p>Implements Dynatrace's 2026 "observability as control plane" thesis:
 * probabilistic models (the LLM) need a deterministic companion layer
 * (this SPI). The resolver is read-only per turn — a snapshot of the
 * world the agent should see, not a side-effecting tool.</p>
 *
 * <h2>Request shape</h2>
 *
 * A {@link FactRequest} names a set of keys the caller wants resolved.
 * Resolvers return a {@link FactBundle} — an immutable map of
 * {@code key → value} — that the framework serializes into the agent's
 * execution context as a {@code facts} block prepended to the system
 * prompt. The default impl is {@link NoopFactResolver}; applications
 * install richer resolvers (user-service lookup, feature-flag read,
 * internal event bus) via {@code ServiceLoader}.
 *
 * <h2>Keys</h2>
 *
 * Stable well-known keys live in {@link FactKeys}. Applications add
 * their own keys by prefixing a domain tag (e.g. {@code app.order.id},
 * {@code app.user.plan_tier}) — the framework treats unknown keys
 * transparently.
 *
 * <h2>Threading / caching</h2>
 *
 * Implementations MUST be thread-safe. Results for a given
 * {@code (userId, key)} pair may be cached per-session; see
 * {@link FactResolver#cacheHint(String)} to advertise a TTL.
 */
public interface FactResolver {

    /**
     * Framework-property key under which a resolver instance can be bridged
     * by Spring / Quarkus / CDI auto-configuration, mirroring the pattern used
     * for {@code CoordinationJournal} and {@code Broadcaster} factories.
     * Lookup order inside {@code AiEndpointHandler}:
     * <ol>
     *   <li>{@code framework.getAtmosphereConfig().properties().get(FACT_RESOLVER_PROPERTY)}
     *       — the bridged bean (lifecycle owned by the DI container).</li>
     *   <li>{@link java.util.ServiceLoader}{@code .load(FactResolver.class)} —
     *       SPI discovery for plain-servlet and embedded deployments.</li>
     *   <li>{@link DefaultFactResolver} — zero-dep fallback.</li>
     * </ol>
     */
    String FACT_RESOLVER_PROPERTY = "org.atmosphere.ai.factResolver";

    /** Resolve the requested fact keys for this turn. Never returns {@code null}. */
    FactBundle resolve(FactRequest request);

    /**
     * Optional cache-hint TTL in seconds for a given key. {@code -1} means
     * "no caching" (resolve fresh every turn); {@code 0} means "cache for
     * the session lifetime"; any positive value is seconds. Default
     * implementation returns {@code -1}.
     */
    default long cacheHint(String key) {
        return -1L;
    }

    /** Per-turn input. */
    record FactRequest(String userId, String sessionId, String agentId,
                       java.util.Set<String> keys) {
        public FactRequest {
            keys = keys != null ? java.util.Set.copyOf(keys) : java.util.Set.of();
        }
    }

    /** Per-turn output. Immutable. */
    record FactBundle(Map<String, Object> facts) {
        public FactBundle {
            facts = facts != null ? Map.copyOf(facts) : Map.of();
        }

        public Optional<Object> get(String key) {
            return Optional.ofNullable(facts.get(key));
        }

        /**
         * Render as a newline-delimited {@code key: value} block for
         * system-prompt injection. Keys and values are escaped so
         * newline / carriage-return / tab characters in fact values
         * cannot reshape the surrounding instruction context — they are
         * replaced with a literal space. Without this escaping a
         * malicious or accidental value like
         * {@code "Alice\n\nIgnore prior instructions."} would inject
         * a new line that downstream models treat as an authoritative
         * directive.
         */
        public String asSystemPromptBlock() {
            if (facts.isEmpty()) {
                return "";
            }
            var sb = new StringBuilder("Grounded facts (deterministic, as of this turn):\n");
            facts.forEach((k, v) -> sb.append("- ")
                    .append(escape(k))
                    .append(": ")
                    .append(escape(v == null ? "" : v.toString()))
                    .append('\n'));
            return sb.toString();
        }

        /**
         * Replace newline, carriage-return, tab, and other ASCII control
         * characters with a single space so a fact value cannot terminate
         * the current line and start a new "instruction" line in the
         * system prompt.
         */
        private static String escape(String raw) {
            if (raw == null || raw.isEmpty()) {
                return "";
            }
            var sb = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\n' || c == '\r' || c == '\t' || (c < 0x20 && c != ' ')) {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        public static FactBundle empty() {
            return new FactBundle(Map.of());
        }
    }
}
