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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation of {@link ModelRouter}. Tracks backend health
 * and routes requests based on the configured {@link FallbackStrategy}.
 *
 * <p>Health tracking uses a simple circuit breaker pattern:</p>
 * <ul>
 *   <li>Consecutive failures increment a failure counter</li>
 *   <li>After {@code maxConsecutiveFailures}, the backend is marked unhealthy</li>
 *   <li>After a cooldown period, the backend is eligible again</li>
 *   <li>A success resets the failure counter</li>
 * </ul>
 */
public class DefaultModelRouter implements ModelRouter {

    private static final Logger logger = LoggerFactory.getLogger(DefaultModelRouter.class);

    private final FallbackStrategy strategy;
    private final int maxConsecutiveFailures;
    private final Duration cooldownPeriod;
    private final ConcurrentHashMap<String, BackendHealth> healthMap = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public DefaultModelRouter() {
        this(FallbackStrategy.FAILOVER);
    }

    public DefaultModelRouter(FallbackStrategy strategy) {
        this(strategy, 3, Duration.ofMinutes(1));
    }

    public DefaultModelRouter(FallbackStrategy strategy, int maxConsecutiveFailures,
                              Duration cooldownPeriod) {
        this.strategy = strategy;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.cooldownPeriod = cooldownPeriod;
    }

    @Override
    public Optional<AiSupport> route(AiRequest request, List<AiSupport> availableBackends,
                                     Set<AiCapability> requiredCapabilities) {
        if (availableBackends.isEmpty()) {
            return Optional.empty();
        }

        // Filter by capabilities
        var eligible = availableBackends.stream()
                .filter(b -> b.capabilities().containsAll(requiredCapabilities))
                .filter(this::isHealthy)
                .toList();

        if (eligible.isEmpty()) {
            // All healthy backends filtered out — try unhealthy ones as last resort
            logger.warn("No healthy backends with required capabilities {}; trying all available",
                    requiredCapabilities);
            eligible = availableBackends.stream()
                    .filter(b -> b.capabilities().containsAll(requiredCapabilities))
                    .toList();
        }

        if (eligible.isEmpty()) {
            logger.error("No backends support required capabilities: {}", requiredCapabilities);
            return Optional.empty();
        }

        return switch (strategy) {
            case NONE -> Optional.of(eligible.getFirst());
            case FAILOVER -> Optional.of(eligible.getFirst());
            case ROUND_ROBIN -> {
                var idx = roundRobinIndex.getAndIncrement() % eligible.size();
                yield Optional.of(eligible.get(Math.abs(idx)));
            }
            case CONTENT_BASED -> routeByContent(request, eligible);
        };
    }

    @Override
    public void reportFailure(AiSupport backend, Throwable error) {
        var health = healthMap.computeIfAbsent(backend.name(), k -> new BackendHealth());
        var failures = health.consecutiveFailures.incrementAndGet();
        health.lastFailure = Instant.now();
        health.lastError = error.getMessage();
        logger.warn("Backend {} failed ({} consecutive): {}",
                backend.name(), failures, error.getMessage());
    }

    @Override
    public void reportSuccess(AiSupport backend) {
        var health = healthMap.get(backend.name());
        if (health != null) {
            health.consecutiveFailures.set(0);
            health.lastError = null;
        }
    }

    private boolean isHealthy(AiSupport backend) {
        var health = healthMap.get(backend.name());
        if (health == null) {
            return true;
        }
        if (health.consecutiveFailures.get() < maxConsecutiveFailures) {
            return true;
        }
        // Check cooldown
        if (health.lastFailure != null) {
            var elapsed = Duration.between(health.lastFailure, Instant.now());
            if (elapsed.compareTo(cooldownPeriod) > 0) {
                // Cooldown expired — give it another chance
                health.consecutiveFailures.set(0);
                return true;
            }
        }
        return false;
    }

    private Optional<AiSupport> routeByContent(AiRequest request, List<AiSupport> eligible) {
        // If request has a model hint, try to match it
        if (request.model() != null) {
            for (var backend : eligible) {
                if (backend.name().contains(request.model().toLowerCase())) {
                    return Optional.of(backend);
                }
            }
        }
        // If request needs tools, prefer backends with TOOL_CALLING
        if (!request.tools().isEmpty()) {
            for (var backend : eligible) {
                if (backend.capabilities().contains(AiCapability.TOOL_CALLING)) {
                    return Optional.of(backend);
                }
            }
        }
        // Default to first available
        return Optional.of(eligible.getFirst());
    }

    private static class BackendHealth {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile Instant lastFailure;
        volatile String lastError;
    }
}
