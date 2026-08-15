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
package org.atmosphere.mcp.client;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Per-server options for {@link McpToolSource}. Two concerns dominate when an
 * agent aggregates tools from several MCP servers:
 *
 * <ul>
 *   <li><strong>Filtering</strong> — expose only a subset of a server's tools
 *       to the model ({@link #toolFilter}), keeping the tool list focused and
 *       the attack surface small.</li>
 *   <li><strong>Renaming</strong> — give each server's tools a unique name so
 *       two servers that both advertise {@code search} don't collide
 *       ({@link #toolNamePrefix} or {@link #nameMapper}). The rename is
 *       <em>display-only</em>: the executor still calls the server's original
 *       tool name on the wire.</li>
 * </ul>
 *
 * <p>It also carries optional server→client callback handlers the modern MCP
 * protocol defines — {@link #elicitationHandler} (the server asks the client for
 * structured input mid-call) and {@link #samplingHandler} (the server asks the
 * client to run an LLM completion). When set, the corresponding client
 * capability is advertised during {@code initialize}.</p>
 *
 * @param toolNamePrefix     prepended to every imported tool name (ignored when
 *                           {@link #nameMapper} is set); never {@code null}
 * @param toolFilter         predicate on the server's <em>original</em> tool
 *                           name; only matching tools are imported; never
 *                           {@code null} (defaults to allow-all)
 * @param nameMapper         maps a server's original tool name to the name the
 *                           model sees; {@code null} falls back to
 *                           {@link #toolNamePrefix}
 * @param elicitationHandler handles a server <em>form</em> elicitation request;
 *                           {@code null} means the client does not advertise
 *                           elicitation at all. URL-mode elicitation
 *                           ({@code McpSchema.ElicitUrlRequest}, added in MCP
 *                           SDK 2.0.0) is deliberately not supported: the
 *                           advertised capability leaves both sub-modes unset,
 *                           so a spec-compliant server never sends one. A
 *                           non-compliant server that sends one anyway is
 *                           rejected by the SDK rather than silently mishandled
 *                           here. Supporting it would mean registering a second
 *                           handler via {@code spec.urlElicitation(...)}
 * @param samplingHandler    handles a server sampling request; {@code null}
 *                           means the client does not advertise sampling
 * @param breakerFailureThreshold consecutive per-tool failures that open the
 *                           circuit breaker; {@code <= 0} disables it
 * @param breakerOpenMillis  cooldown before an open breaker admits a half-open
 *                           probe
 */
public record McpClientOptions(
        String toolNamePrefix,
        Predicate<String> toolFilter,
        UnaryOperator<String> nameMapper,
        Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicitationHandler,
        Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler,
        int breakerFailureThreshold,
        long breakerOpenMillis) {

    /** Consecutive failures that open a tool's breaker unless overridden. */
    public static final int DEFAULT_BREAKER_FAILURE_THRESHOLD = 5;

    /** Cooldown before an open breaker admits a half-open probe, in millis. */
    public static final long DEFAULT_BREAKER_OPEN_MILLIS = 30_000L;

    public McpClientOptions {
        toolNamePrefix = toolNamePrefix == null ? "" : toolNamePrefix;
        if (toolFilter == null) {
            toolFilter = name -> true;
        }
        if (breakerOpenMillis < 0) {
            breakerOpenMillis = DEFAULT_BREAKER_OPEN_MILLIS;
        }
    }

    /**
     * Backwards-compatible constructor predating the circuit-breaker settings.
     * Applies the default threshold and cooldown.
     */
    public McpClientOptions(String toolNamePrefix, Predicate<String> toolFilter,
                            UnaryOperator<String> nameMapper,
                            Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicitationHandler,
                            Function<McpSchema.CreateMessageRequest,
                                    McpSchema.CreateMessageResult> samplingHandler) {
        this(toolNamePrefix, toolFilter, nameMapper, elicitationHandler, samplingHandler,
                DEFAULT_BREAKER_FAILURE_THRESHOLD, DEFAULT_BREAKER_OPEN_MILLIS);
    }

    /** All defaults: no prefix, no filter, no rename, no callbacks. */
    public static McpClientOptions defaults() {
        return builder().build();
    }

    /** Whether the server's original tool name passes the import filter. */
    public boolean includes(String originalName) {
        return toolFilter.test(originalName);
    }

    /** The name the model sees for a server tool (prefix or custom mapper). */
    public String displayName(String originalName) {
        if (nameMapper != null) {
            return nameMapper.apply(originalName);
        }
        return toolNamePrefix + originalName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String toolNamePrefix = "";
        private Predicate<String> toolFilter;
        private UnaryOperator<String> nameMapper;
        private Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicitationHandler;
        private Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler;
        private int breakerFailureThreshold = DEFAULT_BREAKER_FAILURE_THRESHOLD;
        private long breakerOpenMillis = DEFAULT_BREAKER_OPEN_MILLIS;

        /** Prefix every imported tool name (collision avoidance across servers). */
        public Builder toolNamePrefix(String prefix) {
            this.toolNamePrefix = prefix;
            return this;
        }

        /** Import only tools whose original name passes this predicate. */
        public Builder toolFilter(Predicate<String> filter) {
            this.toolFilter = filter;
            return this;
        }

        /** Import only the named tools (allow-list). */
        public Builder includeTools(java.util.Set<String> names) {
            this.toolFilter = names::contains;
            return this;
        }

        /** Custom rename function (takes precedence over the prefix). */
        public Builder nameMapper(UnaryOperator<String> mapper) {
            this.nameMapper = mapper;
            return this;
        }

        /** Register an elicitation handler and advertise the capability. */
        public Builder elicitationHandler(
                Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> handler) {
            this.elicitationHandler = handler;
            return this;
        }

        /** Register a sampling handler and advertise the capability. */
        public Builder samplingHandler(
                Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> handler) {
            this.samplingHandler = handler;
            return this;
        }

        /**
         * Consecutive per-tool failures that open the circuit breaker. Pass
         * {@code 0} (or a negative value) to disable breaking entirely and let
         * every call reach the remote server.
         */
        public Builder breakerFailureThreshold(int threshold) {
            this.breakerFailureThreshold = threshold;
            return this;
        }

        /** Cooldown before an open breaker admits a half-open probe. */
        public Builder breakerOpenMillis(long openMillis) {
            this.breakerOpenMillis = openMillis;
            return this;
        }

        public McpClientOptions build() {
            return new McpClientOptions(toolNamePrefix, toolFilter, nameMapper,
                    elicitationHandler, samplingHandler, breakerFailureThreshold,
                    breakerOpenMillis);
        }
    }
}
