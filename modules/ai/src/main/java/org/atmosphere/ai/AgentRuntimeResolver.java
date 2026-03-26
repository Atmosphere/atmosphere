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

import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Resolves {@link AgentRuntime} implementations from the classpath via
 * {@link ServiceLoader}. Also discovers legacy {@link AiSupport} implementations
 * and wraps them as {@link AgentRuntime} for backward compatibility.
 *
 * <p>Native {@link AgentRuntime} implementations take precedence over wrapped
 * {@link AiSupport} implementations with the same name.</p>
 *
 * @see AgentRuntime
 */
public final class AgentRuntimeResolver {

    private static final Logger logger = LoggerFactory.getLogger(AgentRuntimeResolver.class);

    private AgentRuntimeResolver() {}

    /**
     * Returns the highest-priority available runtime, or
     * {@link BuiltInAgentRuntime} as fallback.
     */
    public static AgentRuntime resolve() {
        var all = resolveAll();
        return all.isEmpty() ? new BuiltInAgentRuntime() : all.getFirst();
    }

    /**
     * Returns all available runtimes sorted by priority (highest first).
     * Discovers both native {@link AgentRuntime} and legacy {@link AiSupport}
     * implementations (wrapped as AgentRuntime).
     */
    public static List<AgentRuntime> resolveAll() {
        var all = new ArrayList<AgentRuntime>();
        var names = new HashSet<String>();

        // 1. Discover native AgentRuntime implementations
        discoverNativeRuntimes(all, names);

        // 2. Discover legacy AiSupport implementations and wrap them
        discoverLegacySupports(all, names);

        all.sort(Comparator.comparingInt(AgentRuntime::priority).reversed());

        if (all.isEmpty()) {
            all.add(new BuiltInAgentRuntime());
            logger.debug("No AgentRuntime on classpath, using built-in");
        }

        return all;
    }

    private static void discoverNativeRuntimes(List<AgentRuntime> all, Set<String> names) {
        try {
            for (var runtime : ServiceLoader.load(AgentRuntime.class)) {
                try {
                    if (runtime.isAvailable()) {
                        all.add(runtime);
                        names.add(runtime.name());
                        logger.debug("AgentRuntime discovered: {} (priority {})",
                                runtime.name(), runtime.priority());
                    }
                } catch (Exception e) {
                    logger.warn("AgentRuntime failed availability check: {}",
                            e.getMessage());
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.debug("ServiceLoader<AgentRuntime> error: {}", e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static void discoverLegacySupports(List<AgentRuntime> all, Set<String> names) {
        try {
            for (var support : ServiceLoader.load(AiSupport.class)) {
                try {
                    if (support.isAvailable() && !names.contains(support.name())) {
                        all.add(new AiSupportBridge(support));
                        names.add(support.name());
                        logger.debug("AiSupport bridged to AgentRuntime: {} (priority {})",
                                support.name(), support.priority());
                    }
                } catch (Exception e) {
                    logger.warn("AiSupport failed availability check: {}",
                            e.getMessage());
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.debug("ServiceLoader<AiSupport> error: {}", e.getMessage());
        }
    }

    /**
     * Wraps a legacy {@link AiSupport} as an {@link AgentRuntime}.
     * Bridges {@code execute()} to {@code stream()} by converting
     * {@link AgentExecutionContext} to {@link AiRequest}.
     */
    static final class AiSupportBridge implements AgentRuntime {

        private final AiSupport delegate;

        AiSupportBridge(AiSupport delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() { return delegate.name(); }

        @Override
        public boolean isAvailable() { return delegate.isAvailable(); }

        @Override
        public int priority() { return delegate.priority(); }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
            delegate.configure(settings);
        }

        @Override
        public Set<AiCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            var request = new AiRequest(
                    context.message(),
                    context.systemPrompt(),
                    context.model(),
                    context.userId(),
                    context.sessionId(),
                    context.agentId(),
                    context.conversationId(),
                    context.metadata(),
                    context.history()
            );
            if (!context.tools().isEmpty()) {
                request = request.withTools(context.tools());
            }
            delegate.stream(request, session);
        }

        AiSupport unwrap() { return delegate; }
    }
}
