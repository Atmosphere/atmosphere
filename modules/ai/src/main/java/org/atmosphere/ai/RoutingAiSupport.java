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
import java.util.Optional;
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
    private volatile AiEnvironment environment;

    public RoutingAiSupport(ModelRouter router, List<AgentRuntime> backends) {
        this(router, backends, Set.of());
    }

    public RoutingAiSupport(ModelRouter router, List<AgentRuntime> backends,
                            Set<AiCapability> requiredCapabilities) {
        this.router = router;
        this.backends = backends;
        this.requiredCapabilities = requiredCapabilities;
    }

    /**
     * Attach the router and every backend it routes across to
     * {@code environment}: they serve that application.
     */
    @Override
    public void attach(AiEnvironment environment) {
        this.environment = environment;
        for (var backend : backends) {
            backend.attach(environment);
        }
    }

    @Override
    public Optional<AiEnvironment> attachedEnvironment() {
        return Optional.ofNullable(environment);
    }

    /** The environment this router was attached to, else {@link AiEnvironment#current()}. */
    AiEnvironment environment() {
        var attached = environment;
        return attached != null ? attached : AiEnvironment.current();
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
        var routingRequest = routingRequest(context);

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

    /**
     * Routes like {@link #execute} and returns the selected backend's own
     * handle, so a cancel reaches the backend's native call. A backend whose
     * {@code executeWithHandle} throws before it returns a handle is reported to
     * the router and the turn fails over to the next backend, as in
     * {@link #execute}.
     */
    @Override
    public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
        var routingRequest = routingRequest(context);
        var selected = router.route(routingRequest, backends, requiredCapabilities);
        if (selected.isEmpty()) {
            session.error(new IllegalStateException("No available AI backend for request"));
            return ExecutionHandle.completed();
        }
        var backend = selected.get();
        try {
            return backend.executeWithHandle(context, new HealthTrackingSession(session, router, backend));
        } catch (RuntimeException e) {
            logger.warn("Backend {} failed, attempting failover: {}", backend.name(), e.getMessage());
            router.reportFailure(backend, e);
            var fallback = router.route(routingRequest, backends, requiredCapabilities);
            if (fallback.isEmpty() || fallback.get() == backend) {
                session.error(e);
                return ExecutionHandle.completed();
            }
            var fallbackBackend = fallback.get();
            try {
                return fallbackBackend.executeWithHandle(context,
                        new HealthTrackingSession(session, router, fallbackBackend));
            } catch (RuntimeException e2) {
                router.reportFailure(fallbackBackend, e2);
                session.error(e2);
                return ExecutionHandle.completed();
            }
        }
    }

    private static AiRequest routingRequest(AgentExecutionContext context) {
        return new AiRequest(
                context.message(), context.systemPrompt(), context.model(),
                context.userId(), context.sessionId(), context.agentId(),
                context.conversationId(), context.metadata(), context.history());
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
     * rather than optimistically after dispatch. Every other call, the
     * session's {@code injectables()} (the turn's watchdog among them) and its
     * state, reaches the routed session unchanged.
     */
    private static final class HealthTrackingSession extends DelegatingStreamingSession {
        private final ModelRouter router;
        private final AgentRuntime backend;

        HealthTrackingSession(StreamingSession delegate, ModelRouter router,
                              AgentRuntime backend) {
            super(delegate);
            this.router = router;
            this.backend = backend;
        }

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

        @Override
        public void emit(AiEvent event) {
            switch (event) {
                case AiEvent.Complete c -> router.reportSuccess(backend);
                case AiEvent.Error e -> router.reportFailure(backend,
                        new IllegalStateException(e.message()));
                default -> { }
            }
            delegate.emit(event);
        }
    }
}
