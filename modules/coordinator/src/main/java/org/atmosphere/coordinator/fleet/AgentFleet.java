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
import org.atmosphere.coordinator.journal.CoordinationJournal;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fleet abstraction injected into {@code @Prompt} methods of {@code @Coordinator}
 * classes. Provides agent discovery and delegation capabilities.
 */
public interface AgentFleet {

    /** Get a proxy to a named agent in this fleet. Throws if not found. */
    AgentProxy agent(String name);

    /** All agents in this fleet (declared via @Fleet). */
    List<AgentProxy> agents();

    /** All currently available agents (filters out unavailable optional agents). */
    List<AgentProxy> available();

    /** Build a call spec (does not execute). */
    AgentCall call(String agentName, String skill, Map<String, Object> args);

    /** Execute calls in parallel. Returns results keyed by agent name. */
    Map<String, AgentResult> parallel(AgentCall... calls);

    /** Execute calls sequentially. Returns the final result. */
    AgentResult pipeline(AgentCall... calls);

    /**
     * Route based on a previous agent result. Evaluates conditions in the
     * routing spec in order; the first match wins. If no condition matches,
     * the {@code otherwise} fallback runs, or a failure result is returned.
     *
     * <pre>{@code
     * var weather = fleet.agent("weather").call("forecast", Map.of("city", city));
     * var result = fleet.route(weather,
     *     route -> route
     *         .when(r -> r.success() && r.text().contains("sunny"),
     *               then -> then.agent("activity").call("outdoor", Map.of()))
     *         .when(r -> r.success(),
     *               then -> then.agent("indoor").call("suggest", Map.of()))
     *         .otherwise(then -> AgentResult.failure("router", "route",
     *               "Weather unavailable", Duration.ZERO))
     * );
     * }</pre>
     *
     * @param input the result to route on
     * @param spec  consumer that builds the routing conditions
     * @return the result from the matched route
     */
    AgentResult route(AgentResult input, Consumer<RoutingSpec> spec);

    /**
     * Evaluate an agent result using all registered {@link ResultEvaluator}s.
     * Returns an empty list if no evaluators are registered.
     */
    default List<Evaluation> evaluate(AgentResult result, AgentCall originalCall) {
        return List.of();
    }

    /**
     * Access the coordination journal for querying past events.
     * Returns {@link CoordinationJournal#NOOP} if journaling is not active.
     */
    default CoordinationJournal journal() {
        return CoordinationJournal.NOOP;
    }

    /**
     * Execute calls in parallel and return cancellable execution handles.
     * Unlike {@link #parallel}, this does not block — callers control when
     * to join and can cancel individual executions.
     *
     * @param calls the calls to dispatch
     * @return map of agent name to execution handle
     */
    default Map<String, AgentExecution> parallelCancellable(AgentCall... calls) {
        var results = new java.util.LinkedHashMap<String, AgentExecution>();
        var nameCount = new java.util.HashMap<String, Integer>();
        for (var agentCall : calls) {
            var name = agentCall.agentName();
            var count = nameCount.merge(name, 1, Integer::sum);
            var key = count == 1 ? name : name + "#" + count;
            var proxy = agent(name);
            results.put(key, proxy.callWithHandle(agentCall.skill(), agentCall.args()));
        }
        return results;
    }

    /**
     * Returns a snapshot of fleet health — agent availability, circuit breaker
     * state, and recent failure counts. Streamable to clients for live dashboards.
     *
     * @return current fleet health snapshot
     */
    default FleetHealth health() {
        var agents = new java.util.LinkedHashMap<String, FleetHealth.AgentHealth>();
        for (var proxy : agents()) {
            var circuitState = proxy instanceof ResilientAgentProxy rp
                    ? rp.circuitBreaker().state() : null;
            agents.put(proxy.name(), new FleetHealth.AgentHealth(
                    proxy.name(), proxy.isAvailable(), circuitState, 0));
        }
        return new FleetHealth(agents, java.time.Instant.now());
    }

    /**
     * Returns a new fleet instance with an additional {@link AgentActivityListener}.
     * Use this in {@code @Prompt} methods to wire per-session streaming:
     *
     * <pre>{@code
     * @Prompt
     * public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
     *     var liveFleet = fleet.withActivityListener(new StreamingActivityListener(session));
     *     var result = liveFleet.agent("weather").call("forecast", Map.of("city", "Montreal"));
     *     session.send(result.text());
     *     session.complete();
     * }
     * }</pre>
     *
     * @param listener the additional listener for this scope
     * @return a new fleet instance with the listener added
     */
    default AgentFleet withActivityListener(AgentActivityListener listener) {
        return this;
    }
}
