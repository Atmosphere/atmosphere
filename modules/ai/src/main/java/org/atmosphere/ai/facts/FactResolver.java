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
 * execution context as a {@code facts} block appended to the <em>end</em>
 * of the system prompt. The block goes at the end (never the front) so the
 * stable persona/skills/schema text forms a byte-identical prefix across
 * turns and provider prompt-prefix caches (Anthropic prompt caching,
 * OpenAI/Gemini prefix caches) keep hitting even though facts like
 * {@code time.now} change. The default impl is {@link DefaultFactResolver}; applications
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
 * <h2>Threading</h2>
 *
 * Implementations MUST be thread-safe. {@code resolve} is invoked once
 * per {@code @Prompt} turn, from the dispatching virtual thread.
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
     * Per-turn input.
     *
     * <p>{@code agentId} is derived by {@code AiEndpointHandler} from
     * the endpoint's {@code pathTemplate} when it matches
     * {@code /atmosphere/agent/<name>} — i.e., endpoints registered
     * through {@code @Agent} or {@code @Coordinator}. Plain
     * {@code @AiEndpoint} declarations at custom paths produce
     * {@code null}; resolvers that need agent-scoped facts on those
     * endpoints MUST receive the agent id out-of-band (HTTP header,
     * session attribute, session id lookup).</p>
     */
    record FactRequest(String userId, String sessionId, String agentId,
                       java.util.Set<String> keys) {
        public FactRequest {
            keys = keys != null ? java.util.Set.copyOf(keys) : java.util.Set.of();
        }
    }

    /** Per-turn output. Immutable. */
    record FactBundle(Map<String, Object> facts) {

        /**
         * First line of the rendered system-prompt block. Also used by
         * {@link #appendStableText(String, String)} to recognize a trailing
         * fact block so later stable additions (structured-output schema,
         * confidence cue) are spliced <em>before</em> it — keeping the
         * volatile facts the absolute suffix of the system prompt.
         */
        public static final String SYSTEM_PROMPT_BLOCK_HEADER =
                "Grounded facts (deterministic, as of this turn):";

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
            var sb = new StringBuilder(SYSTEM_PROMPT_BLOCK_HEADER).append('\n');
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

        /**
         * Append this bundle's rendered block to the <em>end</em> of the
         * given system prompt. This placement is the cache-prefix contract:
         * the stable, developer-authored prompt stays the byte-identical
         * leading prefix of every request so provider prompt-prefix caches
         * (Anthropic prompt caching, OpenAI/Gemini prefix caches) reuse it
         * across turns; the volatile facts (e.g. {@code time.now}) live in
         * a clearly-delimited trailing block. Prepending the facts instead
         * would change the first tokens of every request and defeat those
         * caches framework-wide. Returns the base prompt unchanged when the
         * bundle is empty.
         */
        public String appendToSystemPrompt(String basePrompt) {
            var block = asSystemPromptBlock();
            if (block.isEmpty()) {
                return basePrompt != null ? basePrompt : "";
            }
            if (basePrompt == null || basePrompt.isBlank()) {
                return block;
            }
            return basePrompt + "\n\n" + block;
        }

        /**
         * Append a <em>stable</em> piece of text (structured-output schema,
         * confidence cue) to a system prompt while keeping a trailing
         * grounded-facts block the absolute suffix. When the prompt ends
         * with a block produced by {@link #appendToSystemPrompt(String)},
         * the stable text is spliced in before it; otherwise this is a
         * plain {@code "\n\n"} append. Without the splice, per-turn facts
         * would sit between persona and schema and push the (stable) schema
         * text out of the provider's cacheable prompt prefix.
         */
        public static String appendStableText(String systemPrompt, String stableText) {
            if (stableText == null || stableText.isEmpty()) {
                return systemPrompt != null ? systemPrompt : "";
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                return stableText;
            }
            int idx = indexOfTrailingFactBlock(systemPrompt);
            if (idx < 0) {
                return systemPrompt + "\n\n" + stableText;
            }
            var stablePrefix = systemPrompt.substring(0, idx).stripTrailing();
            var factBlock = systemPrompt.substring(idx);
            return stablePrefix.isEmpty()
                    ? stableText + "\n\n" + factBlock
                    : stablePrefix + "\n\n" + stableText + "\n\n" + factBlock;
        }

        /**
         * Index of a trailing grounded-facts block, or {@code -1}. The block
         * must start at a line boundary and every line after the header must
         * be a {@code "- key: value"} fact line — guaranteed for framework
         * blocks because {@link #escape(String)} strips newlines from values,
         * so nothing user-controlled can masquerade as extra block content.
         */
        private static int indexOfTrailingFactBlock(String prompt) {
            int idx = prompt.lastIndexOf(SYSTEM_PROMPT_BLOCK_HEADER);
            if (idx < 0 || (idx > 0 && prompt.charAt(idx - 1) != '\n')) {
                return -1;
            }
            var rest = prompt.substring(idx + SYSTEM_PROMPT_BLOCK_HEADER.length());
            for (var line : rest.split("\n", -1)) {
                if (!line.isEmpty() && !line.startsWith("- ")) {
                    return -1;
                }
            }
            return idx;
        }

        public static FactBundle empty() {
            return new FactBundle(Map.of());
        }
    }
}
