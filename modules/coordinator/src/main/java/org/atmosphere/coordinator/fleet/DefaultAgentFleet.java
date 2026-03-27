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

/**
 * Default {@link AgentFleet} implementation. Holds a map of agent proxies and
 * provides parallel/pipeline execution patterns using virtual threads.
 */
public final class DefaultAgentFleet implements AgentFleet {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentFleet.class);

    private final Map<String, AgentProxy> proxies;
    private final List<ResultEvaluator> evaluators;

    public DefaultAgentFleet(Map<String, AgentProxy> proxies) {
        this(proxies, List.of());
    }

    public DefaultAgentFleet(Map<String, AgentProxy> proxies,
                             List<ResultEvaluator> evaluators) {
        this.proxies = Map.copyOf(proxies);
        this.evaluators = List.copyOf(evaluators);
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
    public AgentCall call(String agentName, String skill, Map<String, String> args) {
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
                            vtExecutor));
        }

        var results = new LinkedHashMap<String, AgentResult>();
        for (var entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().join());
            } catch (Exception e) {
                logger.error("Parallel call to '{}' failed", entry.getKey(), e);
                results.put(entry.getKey(), AgentResult.failure(
                        entry.getKey(), "", "Parallel call failed: " + e.getMessage(),
                        Duration.ZERO));
            }
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
            last = proxy.call(agentCall.skill(), agentCall.args());
            if (!last.success()) {
                logger.warn("Pipeline step '{}' failed, aborting", agentCall.agentName());
                return last;
            }
        }
        return last;
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
