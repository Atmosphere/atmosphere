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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builder for conditional routing based on an {@link AgentResult}.
 * Evaluates conditions in declaration order; the first match wins.
 *
 * <pre>{@code
 * fleet.route(weatherResult,
 *     route -> route
 *         .when(r -> r.success() && r.text().contains("sunny"),
 *               then -> then.call("activity", "outdoor", Map.of()))
 *         .when(r -> r.success(),
 *               then -> then.call("indoor", "suggest", Map.of()))
 *         .otherwise(then -> AgentResult.failure("router", "route",
 *               "Weather unavailable", Duration.ZERO))
 * );
 * }</pre>
 */
public final class RoutingSpec {

    private final List<Route> routes = new ArrayList<>();
    private Function<AgentFleet, AgentResult> fallback;

    /**
     * Add a conditional route. If the predicate matches, the function is
     * called with the fleet to produce a result.
     *
     * @param condition predicate tested against the input result
     * @param action    function that receives the fleet and returns a result
     * @return this spec for chaining
     */
    public RoutingSpec when(Predicate<AgentResult> condition,
                            Function<AgentFleet, AgentResult> action) {
        routes.add(new Route(condition, action));
        return this;
    }

    /**
     * Add a conditional route that executes a single agent call when matched.
     *
     * @param condition predicate tested against the input result
     * @param call      the agent call to execute
     * @return this spec for chaining
     */
    public RoutingSpec when(Predicate<AgentResult> condition, AgentCall call) {
        routes.add(new Route(condition,
                fleet -> fleet.agent(call.agentName()).call(call.skill(), call.args())));
        return this;
    }

    /**
     * Fallback when no condition matches. If not set and no condition matches,
     * {@link #evaluate} returns a failure result.
     *
     * @param action function that receives the fleet and returns a fallback result
     * @return this spec for chaining
     */
    public RoutingSpec otherwise(Function<AgentFleet, AgentResult> action) {
        this.fallback = action;
        return this;
    }

    /**
     * Evaluate the routing spec against the input result.
     *
     * @param input the result to test conditions against
     * @param fleet the fleet for executing matched actions
     * @return the result from the first matching route, the fallback, or a failure
     */
    public RouteOutcome evaluate(AgentResult input, AgentFleet fleet) {
        for (int i = 0; i < routes.size(); i++) {
            var route = routes.get(i);
            if (route.condition.test(input)) {
                var result = route.action.apply(fleet);
                return new RouteOutcome(result, i, true);
            }
        }
        if (fallback != null) {
            var result = fallback.apply(fleet);
            return new RouteOutcome(result, -1, true);
        }
        return new RouteOutcome(
                AgentResult.failure("router", "route",
                        "No routing condition matched", input.duration()),
                -1, false);
    }

    /**
     * Outcome of route evaluation, including which route index matched.
     */
    public record RouteOutcome(AgentResult result, int matchedIndex, boolean matched) {}

    private record Route(Predicate<AgentResult> condition,
                         Function<AgentFleet, AgentResult> action) {}
}
