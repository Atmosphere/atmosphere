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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.coordinator.evaluation.Evaluation;
import org.atmosphere.coordinator.evaluation.ResultEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Default {@link AgentFleet} implementation. Holds a map of agent proxies and
 * provides parallel/pipeline execution patterns using virtual threads.
 */
public final class DefaultAgentFleet implements AgentFleet {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentFleet.class);

    /** Default per-agent timeout for parallel calls: 2 minutes. */
    static final long DEFAULT_PARALLEL_TIMEOUT_MS = 120_000L;

    private final Map<String, AgentProxy> proxies;
    private final List<ResultEvaluator> evaluators;
    private final long parallelTimeoutMs;

    public DefaultAgentFleet(Map<String, AgentProxy> proxies) {
        this(proxies, List.of());
    }

    public DefaultAgentFleet(Map<String, AgentProxy> proxies,
                             List<ResultEvaluator> evaluators) {
        this(proxies, evaluators, DEFAULT_PARALLEL_TIMEOUT_MS);
    }

    public DefaultAgentFleet(Map<String, AgentProxy> proxies,
                             List<ResultEvaluator> evaluators,
                             long parallelTimeoutMs) {
        this.proxies = Map.copyOf(proxies);
        this.evaluators = List.copyOf(evaluators);
        this.parallelTimeoutMs = parallelTimeoutMs;
    }

    @Override
    public AgentProxy agent(String name) {
        var proxy = proxies.get(name);
        if (proxy == null) {
            throw new IllegalArgumentException(
                    "No agent named '" + name + "' in fleet. Available: " + proxies.keySet());
        }
        return proxy;
    }

    @Override
    public List<AgentProxy> agents() {
        return List.copyOf(proxies.values());
    }

    @Override
    public List<AgentProxy> available() {
        return proxies.values().stream()
                .filter(AgentProxy::isAvailable)
                .toList();
    }

    @Override
    public AgentCall call(String agentName, String skill, Map<String, Object> args) {
        return new AgentCall(agentName, skill, args);
    }

    @Override
    public Map<String, AgentResult> parallel(AgentCall... calls) {
        logger.debug("Parallel fan-out to {} agents", calls.length);

        ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new LinkedHashMap<String, CompletableFuture<AgentResult>>();
        var nameCount = new HashMap<String, Integer>();
        for (var agentCall : calls) {
            var name = agentCall.agentName();
            var count = nameCount.merge(name, 1, Integer::sum);
            var key = count == 1 ? name : name + "#" + count;
            var proxy = agent(name);
            futures.put(key,
                    CompletableFuture.supplyAsync(
                            () -> proxy.call(agentCall.skill(), agentCall.args()),
                            vtExecutor)
                            .orTimeout(parallelTimeoutMs, TimeUnit.MILLISECONDS));
        }

        var results = new LinkedHashMap<String, AgentResult>();
        var failed = false;
        for (var entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().join());
            } catch (Exception e) {
                failed = true;
                var cause = e.getCause();
                var msg = cause instanceof TimeoutException
                        ? "Agent timed out after " + parallelTimeoutMs + "ms"
                        : "Parallel call failed: " + e.getMessage();
                logger.error("Parallel call to '{}' failed: {}", entry.getKey(), msg);
                results.put(entry.getKey(), AgentResult.failure(
                        entry.getKey(), "", msg, Duration.ZERO));
            }
        }

        if (failed) {
            futures.values().forEach(f -> f.cancel(true));
        }

        vtExecutor.close();
        logger.debug("Parallel fan-out complete: {} results", results.size());
        return results;
    }

    @Override
    public AgentResult pipeline(AgentCall... calls) {
        logger.debug("Pipeline execution of {} steps", calls.length);
        AgentResult last = null;
        for (var agentCall : calls) {
            var proxy = agent(agentCall.agentName());
            // Merge previous result into args so pipeline steps can chain
            var args = agentCall.args();
            if (last != null) {
                var merged = new LinkedHashMap<>(args);
                merged.put("_previous_result", last.text());
                args = Map.copyOf(merged);
            }
            last = proxy.call(agentCall.skill(), args);
            if (!last.success()) {
                logger.warn("Pipeline step '{}' failed, aborting", agentCall.agentName());
                return last;
            }
        }
        return last;
    }

    @Override
    public AgentResult route(AgentResult input, Consumer<RoutingSpec> spec) {
        logger.debug("Evaluating route for input from '{}'", input.agentName());
        var routing = new RoutingSpec();
        spec.accept(routing);
        var outcome = routing.evaluate(input, this);
        if (outcome.matched()) {
            logger.debug("Route matched (index={}), result from '{}'",
                    outcome.matchedIndex(), outcome.result().agentName());
        } else {
            logger.debug("No route matched for input from '{}'", input.agentName());
        }
        return outcome.result();
    }

    @Override
    public List<Evaluation> evaluate(AgentResult result, AgentCall originalCall) {
        return evaluators.stream()
                .map(e -> {
                    try {
                        return e.evaluate(result, originalCall);
                    } catch (Exception ex) {
                        logger.warn("Evaluator '{}' failed for agent '{}'",
                                e.name(), result.agentName(), ex);
                        return Evaluation.fail(0.0,
                                "Evaluator error: " + ex.getMessage());
                    }
                })
                .toList();
    }
}
