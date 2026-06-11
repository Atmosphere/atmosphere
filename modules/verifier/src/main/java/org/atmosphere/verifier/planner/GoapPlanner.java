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
package org.atmosphere.verifier.planner;

import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic Goal-Oriented Action Planning: given an initial world state,
 * a goal state, and a set of {@link GoapAction}s, search for the shortest
 * ordered plan whose execution reaches the goal, and emit it as a verifier
 * {@link Workflow}.
 *
 * <p>This is the deterministic counterpart to LLM-emitted plans: instead of
 * prompting a model for a workflow, the planner <em>derives</em> one by
 * forward breadth-first search over world states (so the result is the
 * fewest-step plan), then hands it to the same static verifier chain. The
 * search is exhaustive within bounds and reproducible — identical inputs
 * always yield the identical plan.</p>
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * The search is bounded on two axes: {@code maxPlanLength} caps how deep a
 * plan may grow, and {@code maxExpansions} caps how many states are explored.
 * A domain with a large branching factor cannot make the planner run
 * unbounded; it returns {@link Optional#empty()} (logged) when a bound is hit
 * before the goal is reached, rather than exhausting memory.
 */
public final class GoapPlanner {

    private static final Logger logger = LoggerFactory.getLogger(GoapPlanner.class);

    /** Default cap on plan length (number of actions). */
    public static final int DEFAULT_MAX_PLAN_LENGTH = 32;
    /** Default cap on states explored before giving up. */
    public static final int DEFAULT_MAX_EXPANSIONS = 10_000;

    private final int maxPlanLength;
    private final int maxExpansions;

    public GoapPlanner() {
        this(DEFAULT_MAX_PLAN_LENGTH, DEFAULT_MAX_EXPANSIONS);
    }

    public GoapPlanner(int maxPlanLength, int maxExpansions) {
        if (maxPlanLength <= 0) {
            throw new IllegalArgumentException("maxPlanLength must be > 0, got " + maxPlanLength);
        }
        if (maxExpansions <= 0) {
            throw new IllegalArgumentException("maxExpansions must be > 0, got " + maxExpansions);
        }
        this.maxPlanLength = maxPlanLength;
        this.maxExpansions = maxExpansions;
    }

    /**
     * Search for a plan reaching {@code goalState} from {@code initialState}.
     *
     * @param goal         human-facing goal description carried into the
     *                     emitted workflow; must be non-blank
     * @param initialState predicates true at the start
     * @param goalState    predicates that must all be true at the end
     * @param actions      available actions
     * @return the shortest workflow reaching the goal, or empty if the goal
     *         is unreachable within the configured bounds
     */
    public Optional<Workflow> plan(String goal, Set<String> initialState,
                                   Set<String> goalState, List<GoapAction> actions) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        var start = Set.copyOf(initialState);
        var target = Set.copyOf(goalState);

        // Already satisfied — the empty plan reaches the goal.
        if (start.containsAll(target)) {
            return Optional.of(new Workflow(goal, List.of()));
        }

        var queue = new ArrayDeque<Node>();
        var visited = new HashSet<Set<String>>();
        queue.add(new Node(start, List.of()));
        visited.add(start);
        var expansions = 0;

        while (!queue.isEmpty()) {
            if (++expansions > maxExpansions) {
                logger.debug("GOAP search hit maxExpansions={} before reaching goal {}",
                        maxExpansions, target);
                return Optional.empty();
            }
            var node = queue.poll();
            if (node.plan.size() >= maxPlanLength) {
                continue;
            }
            for (var action : actions) {
                if (!action.applicableIn(node.state)) {
                    continue;
                }
                var nextState = new LinkedHashSet<>(node.state);
                if (!nextState.addAll(action.effects())) {
                    // No new predicate — this action makes no progress here.
                    continue;
                }
                var frozen = Set.copyOf(nextState);
                if (!visited.add(frozen)) {
                    continue;
                }
                var nextPlan = new ArrayList<>(node.plan);
                nextPlan.add(action);
                if (frozen.containsAll(target)) {
                    return Optional.of(toWorkflow(goal, nextPlan));
                }
                queue.add(new Node(frozen, List.copyOf(nextPlan)));
            }
        }
        logger.debug("GOAP search exhausted {} state(s); goal {} unreachable with the given actions",
                visited.size(), target);
        return Optional.empty();
    }

    private static Workflow toWorkflow(String goal, List<GoapAction> plan) {
        var steps = new ArrayList<WorkflowStep>(plan.size());
        for (var action : plan) {
            steps.add(new WorkflowStep(action.label(),
                    new ToolCallNode(action.toolName(), action.arguments(), action.resultBinding())));
        }
        return new Workflow(goal, steps);
    }

    private record Node(Set<String> state, List<GoapAction> plan) {
    }
}
