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

import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.intent.IntentDecision;
import org.atmosphere.ai.intent.IntentHandler;
import org.atmosphere.ai.intent.IntentRoute;
import org.atmosphere.ai.intent.IntentRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * The ONE intent-routing step shared by both dispatch entry modes — the
 * {@code @AiEndpoint} path ({@link AiStreamingSession}) and {@link AiPipeline} —
 * so a request is routed identically whichever way it arrived (Correctness
 * Invariant #7). Both callers invoke it after admission (guardrails, policies,
 * per-request scope) and before composing the LLM decorator chain.
 *
 * <p>Every path out of a handled route leaves the session terminal: the reply
 * is sent and the session completed, or the session is errored (Invariant #2).
 * A {@link IntentRoute.Llm} route leaves the session untouched apart from the
 * wire signal, and the caller continues its normal dispatch.</p>
 */
final class IntentDispatch {

    private static final Logger logger = LoggerFactory.getLogger(IntentDispatch.class);

    /** Approval tool-name prefix for a CONFIRM-tier confirmation. */
    static final String CONFIRM_TOOL_PREFIX = "intent:";

    private IntentDispatch() {
    }

    /** What the caller does next. */
    enum Outcome {
        /** The request continues down the normal LLM dispatch path. */
        CONTINUE_TO_LLM,
        /** A handler or human route answered (or errored) the turn; the caller returns. */
        HANDLED
    }

    /**
     * Inputs of one routing step.
     *
     * @param routing   the routing in scope
     * @param message   the text to classify: the user message as admission left it
     * @param rawMessage the message as the caller received it, recorded in memory
     *                  exactly like the LLM path's memory layer does
     * @param request   the admitted request
     * @param base      the session the turn writes to
     * @param memory    conversation memory, or {@code null}
     * @param memoryKey the key the memory layer stores under
     * @param approvals the session's approval strategy, used for CONFIRM
     * @param cancelled whether the turn was cancelled (client disconnect) while
     *                  waiting for a confirmation
     */
    record Step(IntentRouting routing, String message, String rawMessage, AiRequest request,
                StreamingSession base, AiConversationMemory memory, String memoryKey,
                ApprovalStrategy approvals, BooleanSupplier cancelled) {
    }

    static Outcome route(Step step) {
        var routing = step.routing();
        var classification = routing.classify(step.message());
        var tier = classification.tier();
        var reason = classification.reason();
        IntentRoute route = routing.humanRoute();

        var chosen = classification.choice().flatMap(routing::route);
        if (chosen.isPresent()) {
            var candidate = chosen.get();
            if (candidate instanceof IntentRoute.Human) {
                // Escalation goes to the human route anyway: no confirmation to ask.
                route = candidate;
            } else if (tier == ConfidenceRoute.ACT) {
                route = candidate;
            } else if (tier == ConfidenceRoute.CONFIRM) {
                var confirmed = confirm(step, candidate, classification.confidence());
                if (confirmed == Confirmation.CANCELLED) {
                    step.base().error(new CancellationException(
                            "intent confirmation cancelled before the requester answered"));
                    return Outcome.HANDLED;
                }
                if (confirmed == Confirmation.APPROVED) {
                    route = candidate;
                    reason = reason + "; confirmed by the requester";
                } else {
                    reason = reason + "; confirmation " + confirmed.name().toLowerCase(java.util.Locale.ROOT);
                }
            }
        }

        var decision = new IntentDecision(route.name(), classification.choice(), tier,
                classification.confidence(), reason, step.message(), step.request());
        signal(step.base(), decision);
        logger.debug("Intent routed to '{}': {}", route.name(), reason);
        var observer = routing.onDecision();
        if (observer != null) {
            try {
                observer.accept(decision);
            } catch (RuntimeException e) {
                // An observer must not take down a turn whose route is decided.
                logger.warn("IntentRouting observer failed for route {}", route.name(), e);
            }
        }

        return switch (route) {
            case IntentRoute.Llm ignored -> Outcome.CONTINUE_TO_LLM;
            case IntentRoute.Handler handler -> answer(step, handler.handler(), decision);
            case IntentRoute.Human human -> answer(step, human.handler(), decision);
        };
    }

    /** The wire signal, before any reply frame. */
    private static void signal(StreamingSession session, IntentDecision decision) {
        session.sendMetadata(IntentRouting.ROUTE_METADATA_KEY, decision.route());
        session.sendMetadata(IntentRouting.TIER_METADATA_KEY, decision.tier().name());
        decision.choice().ifPresent(c -> session.sendMetadata(IntentRouting.CHOICE_METADATA_KEY, c));
        var aggregate = decision.confidence().aggregate();
        if (aggregate.isPresent()) {
            session.sendMetadata(IntentRouting.CONFIDENCE_METADATA_KEY, aggregate.getAsDouble());
        }
    }

    private static Outcome answer(Step step, IntentHandler handler, IntentDecision decision) {
        StreamingSession target = step.base();
        if (step.memory() != null) {
            // The exchange joins the conversation, so the next LLM turn sees it.
            target = new MemoryCapturingSession(target, step.memory(), step.memoryKey(),
                    step.rawMessage());
        }
        String reply;
        try {
            reply = handler.handle(decision);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            target.error(e);
            return Outcome.HANDLED;
        } catch (Exception e) {
            logger.warn("Intent route '{}' failed", decision.route(), e);
            target.error(e);
            return Outcome.HANDLED;
        }
        if (reply != null && !reply.isEmpty()) {
            target.send(reply);
        }
        target.complete();
        return Outcome.HANDLED;
    }

    private enum Confirmation { APPROVED, DENIED, TIMED_OUT, CANCELLED }

    private static Confirmation confirm(Step step, IntentRoute candidate, AiConfidence confidence) {
        var approvals = step.approvals();
        if (approvals == null) {
            // No channel to ask on: an unconfirmed choice never acts.
            return Confirmation.DENIED;
        }
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("route", candidate.name());
        arguments.put("tier", ConfidenceRoute.CONFIRM.name());
        confidence.aggregate().ifPresent(v -> arguments.put("confidence", v));
        var description = candidate.description().isBlank() ? "" : " (" + candidate.description() + ")";
        var approval = new PendingApproval(
                ApprovalRegistry.generateId(),
                CONFIRM_TOOL_PREFIX + candidate.name(),
                arguments,
                "Route this request to '" + candidate.name() + "'" + description + "?",
                step.base().sessionId(),
                Instant.now().plus(step.routing().confirmTimeout()));
        ApprovalStrategy.ApprovalOutcome outcome;
        try {
            outcome = approvals.awaitApprovalDetailed(approval, step.base()).outcome();
        } catch (RuntimeException e) {
            logger.warn("Intent confirmation for '{}' failed; escalating", candidate.name(), e);
            outcome = ApprovalStrategy.ApprovalOutcome.DENIED;
        }
        if (outcome == ApprovalStrategy.ApprovalOutcome.APPROVED) {
            return Confirmation.APPROVED;
        }
        // A disconnect cancels pending approvals as denials; it is not the
        // requester's answer, so nobody is escalated for a client that left.
        if (step.cancelled() != null && step.cancelled().getAsBoolean()) {
            return Confirmation.CANCELLED;
        }
        return outcome == ApprovalStrategy.ApprovalOutcome.TIMED_OUT
                ? Confirmation.TIMED_OUT : Confirmation.DENIED;
    }

    /**
     * The routing for this request: request metadata wins over the default.
     * The metadata entry is consumed by the caller so it never reaches the provider.
     */
    static Optional<IntentRouting> resolve(java.util.Map<String, Object> metadata, IntentRouting fallback) {
        var fromRequest = IntentRouting.from(metadata);
        return Optional.ofNullable(fromRequest != null ? fromRequest : fallback);
    }
}
