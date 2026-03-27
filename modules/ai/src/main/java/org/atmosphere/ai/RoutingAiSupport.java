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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * {@link AgentRuntime} that routes requests across multiple backends via a
 * {@link ModelRouter}. Retries once on failure using the next available backend.
 */
public class RoutingAiSupport implements AgentRuntime {

    private static final Logger logger = LoggerFactory.getLogger(RoutingAiSupport.class);

    private final ModelRouter router;
    private final List<AgentRuntime> backends;
    private final Set<AiCapability> requiredCapabilities;

    public RoutingAiSupport(ModelRouter router, List<AgentRuntime> backends) {
        this(router, backends, Set.of());
    }

    public RoutingAiSupport(ModelRouter router, List<AgentRuntime> backends,
                            Set<AiCapability> requiredCapabilities) {
        this.router = router;
        this.backends = backends;
        this.requiredCapabilities = requiredCapabilities;
    }

    @Override
    public String name() {
        return "routing(" + backends.stream().map(AgentRuntime::name)
                .reduce((a, b) -> a + "," + b).orElse("none") + ")";
    }

    @Override
    public boolean isAvailable() {
        return backends.stream().anyMatch(AgentRuntime::isAvailable);
    }

    @Override
    public int priority() {
        return backends.stream().mapToInt(AgentRuntime::priority).max().orElse(0);
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        for (var backend : backends) {
            backend.configure(settings);
        }
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        // Build a minimal AiRequest for routing (router uses message/model for selection)
        var routingRequest = new AiRequest(
                context.message(), context.systemPrompt(), context.model(),
                context.userId(), context.sessionId(), context.agentId(),
                context.conversationId(), context.metadata(), context.history());

        var selected = router.route(routingRequest, backends, requiredCapabilities);
        if (selected.isEmpty()) {
            session.error(new IllegalStateException("No available AI backend for request"));
            return;
        }

        var backend = selected.get();
        try {
            var healthSession = new HealthTrackingSession(session, router, backend);
            backend.execute(context, healthSession);
        } catch (Exception e) {
            logger.warn("Backend {} failed, attempting failover: {}",
                    backend.name(), e.getMessage());
            router.reportFailure(backend, e);

            var fallback = router.route(routingRequest, backends, requiredCapabilities);
            if (fallback.isPresent() && fallback.get() != backend) {
                try {
                    var fallbackBackend = fallback.get();
                    var healthSession = new HealthTrackingSession(
                            session, router, fallbackBackend);
                    fallbackBackend.execute(context, healthSession);
                } catch (Exception e2) {
                    router.reportFailure(fallback.get(), e2);
                    session.error(e2);
                }
            } else {
                session.error(e);
            }
        }
    }

    @Override
    public Set<AiCapability> capabilities() {
        return backends.stream()
                .flatMap(b -> b.capabilities().stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    List<AgentRuntime> backends() {
        return backends;
    }

    ModelRouter router() {
        return router;
    }

    /**
     * Session decorator that reports health to the router on completion or error,
     * rather than optimistically after dispatch.
     */
    private static class HealthTrackingSession implements StreamingSession {
        private final StreamingSession delegate;
        private final ModelRouter router;
        private final AgentRuntime backend;

        HealthTrackingSession(StreamingSession delegate, ModelRouter router,
                              AgentRuntime backend) {
            this.delegate = delegate;
            this.router = router;
            this.backend = backend;
        }

        @Override public String sessionId() { return delegate.sessionId(); }
        @Override public void send(String text) { delegate.send(text); }
        @Override public void sendMetadata(String key, Object value) { delegate.sendMetadata(key, value); }
        @Override public void progress(String message) { delegate.progress(message); }
        @Override public boolean isClosed() { return delegate.isClosed(); }

        @Override
        public void complete() {
            router.reportSuccess(backend);
            delegate.complete();
        }

        @Override
        public void complete(String summary) {
            router.reportSuccess(backend);
            delegate.complete(summary);
        }

        @Override
        public void error(Throwable t) {
            router.reportFailure(backend, t);
            delegate.error(t);
        }
    }
}
