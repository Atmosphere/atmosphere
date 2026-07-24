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
     * Consensus dispatch — runs every supplied call in parallel and returns
     * the {@link AgentResult} whose normalized text is shared by the most
     * peers (the "majority answer"). Ties are broken by insertion order:
     * the first agent whose vote belongs to a top-tier cohort wins.
     *
     * <p>Normalization for the count is {@code text.strip().toLowerCase(Locale.ROOT)}
     * so trivial whitespace / capitalisation differences across providers
     * collapse to the same vote. The returned result is the original,
     * un-normalised {@link AgentResult} — text and metadata preserved.</p>
     *
     * <p>When every dispatched call fails, returns a synthetic failure
     * result attributed to {@code "vote"} so callers do not have to
     * separately null-check or scan the input list.</p>
     *
     * @param calls the calls to fan out — typically the same prompt across
     *              different model bindings (e.g. one sub-agent per model)
     * @return the winning result, or a synthetic failure if every peer failed
     */
    default AgentResult vote(AgentCall... calls) {
        if (calls == null || calls.length == 0) {
            return AgentResult.failure("vote", "",
                    "vote() requires at least one AgentCall",
                    java.time.Duration.ZERO);
        }
        var results = parallel(calls);
        var successful = results.values().stream()
                .filter(AgentResult::success)
                .toList();
        if (successful.isEmpty()) {
            return AgentResult.failure("vote", "",
                    "All " + results.size() + " peer(s) failed",
                    java.time.Duration.ZERO);
        }
        // Tally normalised text → count. LinkedHashMap preserves the
        // first-seen order so the tie-breaker remains deterministic.
        var tally = new java.util.LinkedHashMap<String, Integer>();
        for (var r : successful) {
            tally.merge(normaliseForVote(r.text()), 1, Integer::sum);
        }
        var maxVotes = tally.values().stream().max(Integer::compare).orElse(0);
        for (var r : successful) {
            if (tally.get(normaliseForVote(r.text())) == maxVotes) {
                return r;
            }
        }
        // Unreachable: maxVotes came from this same set. Defensive return.
        return successful.get(0);
    }

    /** Normalisation used by {@link #vote} so trivial whitespace / case
     *  differences across providers do not split the vote.
     *
     *  @param text raw response text; may be null
     *  @return trimmed, lower-cased text suitable for equality comparison
     */
    private static String normaliseForVote(String text) {
        return text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
    }

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
     * Well-known {@link AgentCall} argument key under which {@link #refineUntil}
     * injects the aggregated evaluator feedback from the previous attempt so the
     * worker agent can correct its next response. Mirrors the {@code
     * _previous_result} key {@link #pipeline} uses to chain steps.
     */
    String REFINE_FEEDBACK_KEY = "_refine_feedback";

    /**
     * Safety cap on refinement turns applied by {@link #refineUntil} when the
     * worker agent's configured {@link AgentLimits#maxTurns()} is unbounded
     * ({@link Integer#MAX_VALUE} — the default when {@code @AgentRef(maxTurns=...)}
     * is not set). Guarantees the supervisor loop always terminates even with no
     * explicit budget (Correctness Invariant #2/#3 — bounded work, no unbounded
     * growth). Explicit finite budgets are honoured as the caller's choice.
     */
    int DEFAULT_MAX_REFINE_TURNS = 8;

    /**
     * Evaluator-driven refinement (supervisor / reflection) loop. Dispatches the
     * worker {@code call}, runs the registered {@link ResultEvaluator}s via
     * {@link #evaluate}, and — while any evaluator FAILS — re-dispatches the same
     * skill with the aggregated evaluator feedback merged into the call args
     * under {@link #REFINE_FEEDBACK_KEY}, until an evaluator verdict passes or the
     * turn budget is exhausted. Returns the first passing result, or (on
     * exhaustion / cancellation) the best-scoring attempt seen.
     *
     * <p>The turn budget is the worker agent's configured
     * {@link AgentProxy#maxTurns()} (sourced from {@code @AgentRef(maxTurns=...)}
     * → {@link AgentLimits#maxTurns()}). See {@link #refineUntil(AgentCall, int)}
     * for the exact bounding, cancellation, and terminal-path semantics.</p>
     *
     * @param call the worker call to dispatch and refine; must not be {@code null}
     * @return the passing result, or the best/last attempt on exhaustion
     */
    default AgentResult refineUntil(AgentCall call) {
        if (call == null) {
            return AgentResult.failure("refine", "",
                    "refineUntil() requires a non-null AgentCall",
                    java.time.Duration.ZERO);
        }
        return refineUntil(call, agent(call.agentName()).maxTurns());
    }

    /**
     * Evaluator-driven refinement loop with an explicit turn budget. Behaves as
     * {@link #refineUntil(AgentCall)} but bounds the loop to {@code maxTurns}
     * total dispatches instead of reading the worker agent's configured limit.
     *
     * <p><b>Bounding.</b> The loop runs at most {@code min(maxTurns, }
     * effective{@code )} total dispatches. An unbounded budget
     * ({@link Integer#MAX_VALUE}) is clamped to {@link #DEFAULT_MAX_REFINE_TURNS}
     * so the loop always terminates; explicit finite budgets are honoured as-is
     * (Correctness Invariant #3 — no unbounded growth).</p>
     *
     * <p><b>Verdict.</b> After each dispatch the registered evaluators run via
     * {@link #evaluate}. An empty evaluator set means "nothing to refine
     * against" — the first result is returned immediately. Otherwise the attempt
     * passes only when every evaluation {@link Evaluation#passed() passed}; on
     * failure the aggregated failing {@link Evaluation#reason() reasons} are
     * merged into the next call's args under {@link #REFINE_FEEDBACK_KEY}.</p>
     *
     * <p><b>Terminal paths.</b> On a passing verdict the passing result is
     * returned. On budget exhaustion the highest-scoring attempt is returned
     * (a successful result outranks a failed one at equal score), never a
     * half-completed state (Correctness Invariant #2). Cooperative cancellation
     * is honoured: an interrupt on the coordinating thread stops the loop at the
     * next turn boundary and returns the best attempt gathered so far.</p>
     *
     * <p>Implemented in terms of {@link #agent} and {@link #evaluate}, so
     * journaling / interception / governance decorators apply to every turn and
     * behaviour is identical across fleet wrappings (Correctness Invariant #7 —
     * Mode Parity).</p>
     *
     * @param call     the worker call to dispatch and refine; must not be {@code null}
     * @param maxTurns maximum total dispatch attempts; must be {@code >= 1}
     * @return the passing result, or the best/last attempt on exhaustion
     * @throws IllegalArgumentException if {@code maxTurns < 1}
     */
    default AgentResult refineUntil(AgentCall call, int maxTurns) {
        if (call == null) {
            return AgentResult.failure("refine", "",
                    "refineUntil() requires a non-null AgentCall",
                    java.time.Duration.ZERO);
        }
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be >= 1, got: " + maxTurns);
        }
        // Clamp the unbounded sentinel to a concrete cap so the loop always
        // terminates; honour explicit finite budgets as the caller's choice.
        var budget = maxTurns == Integer.MAX_VALUE ? DEFAULT_MAX_REFINE_TURNS : maxTurns;

        var current = call;
        AgentResult best = null;
        var bestScore = Double.NEGATIVE_INFINITY;

        for (var turn = 1; turn <= budget; turn++) {
            // Cooperative cancellation — a caller that interrupts the
            // coordinating thread stops the loop at a turn boundary and returns
            // the best result gathered so far (Correctness Invariant #2).
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            var result = agent(current.agentName()).call(current.skill(), current.args());
            var evaluations = evaluate(result, current);

            // No evaluators registered — nothing to refine against, the first
            // dispatch is the answer.
            if (evaluations.isEmpty() || evaluations.stream().allMatch(Evaluation::passed)) {
                return result;
            }
            // Track the best-scoring attempt so an exhausted budget still returns
            // the closest result. Ties favour the later (more-refined) attempt,
            // unless doing so would downgrade a successful result to a failed one.
            var score = aggregateScore(evaluations);
            if (best == null || score > bestScore
                    || (score == bestScore && (result.success() || !best.success()))) {
                best = result;
                bestScore = score;
            }
            // Feed the aggregated failing feedback into the next attempt.
            current = withRefineFeedback(current, feedbackFrom(evaluations));
        }
        // Budget exhausted (or cancelled) with no passing verdict — return the
        // best attempt. `best` is only null if we were cancelled before the
        // first dispatch, in which case a synthetic failure is the terminal state.
        return best != null ? best
                : AgentResult.failure(call.agentName(), call.skill(),
                        "refineUntil() produced no result (cancelled before first dispatch)",
                        java.time.Duration.ZERO);
    }

    /** Mean evaluation score used to rank refinement attempts. */
    private static double aggregateScore(List<Evaluation> evaluations) {
        return evaluations.stream().mapToDouble(Evaluation::score).average().orElse(0.0);
    }

    /** Join the failing evaluations' reasons into a single feedback string. */
    private static String feedbackFrom(List<Evaluation> evaluations) {
        var sb = new StringBuilder();
        for (var eval : evaluations) {
            if (eval.passed()) {
                continue;
            }
            var reason = eval.reason();
            if (reason == null || reason.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(reason);
        }
        return sb.toString();
    }

    /** Return a copy of {@code call} with {@code feedback} under {@link #REFINE_FEEDBACK_KEY}. */
    private static AgentCall withRefineFeedback(AgentCall call, String feedback) {
        var merged = new java.util.LinkedHashMap<>(call.args());
        merged.put(REFINE_FEEDBACK_KEY, feedback);
        return new AgentCall(call.agentName(), call.skill(), merged);
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

    /**
     * Returns a fleet view whose child dispatches carry {@code parentRunId} as
     * the coordinator's tape run id ({@code atmosphere.tape.parentRunId}) on
     * each outgoing wire message. The dispatched agent's tape run records it as
     * its parent run, so the whole multi-agent coordination can be replayed as
     * a tree. Call it in a {@code @Prompt} with the coordinator's own run id:
     *
     * <pre>{@code
     * var teamFleet = fleet.withParentRun(session.runId().orElse(null));
     * var results = teamFleet.parallel(...);
     * }</pre>
     *
     * <p>A {@code null}/blank id returns {@code this}. Default returns
     * {@code this} for fleets that don't propagate a parent run.</p>
     *
     * @param parentRunId the coordinator's tape run id, or {@code null}
     * @return a fleet view that stamps the parent run on each dispatch
     */
    default AgentFleet withParentRun(String parentRunId) {
        return this;
    }

    /**
     * Wrap this fleet with a {@link FleetInterceptor} that evaluates every
     * dispatch before it leaves the coordinator. Multiple interceptors
     * compose through chained calls — the most recently added runs last,
     * and any non-{@code Proceed} decision short-circuits the chain.
     *
     * <p>Governance wiring example — scope check at the agent-to-agent
     * boundary:</p>
     * <pre>{@code
     * var governed = fleet
     *     .withInterceptor(call -> policy.evaluate(
     *             PolicyContext.preAdmission(
     *                     new AiRequest(call.skill() + " " + call.args(), ...)))
     *         instanceof PolicyDecision.Deny deny
     *             ? FleetInterceptor.Decision.deny(deny.reason())
     *             : FleetInterceptor.Decision.proceed());
     * var result = governed.agent("research").call("web_search", args);
     * }</pre>
     *
     * @param interceptor the per-dispatch gate; denial yields a synthetic
     *                    failed {@link AgentResult}, rewrite forwards a
     *                    modified call, proceed admits unchanged
     * @return a new fleet instance with the interceptor added
     */
    default AgentFleet withInterceptor(FleetInterceptor interceptor) {
        if (interceptor == null) {
            throw new IllegalArgumentException("interceptor must not be null");
        }
        if (this instanceof InterceptingAgentFleet existing) {
            var combined = new java.util.ArrayList<>(existing.interceptors());
            combined.add(interceptor);
            return new InterceptingAgentFleet(existing.unwrap(), combined);
        }
        return new InterceptingAgentFleet(this, java.util.List.of(interceptor));
    }
}
