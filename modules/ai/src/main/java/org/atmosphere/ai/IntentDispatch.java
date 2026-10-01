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
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.intent.IntentDecision;
import org.atmosphere.ai.intent.IntentHandler;
import org.atmosphere.ai.intent.IntentRoute;
import org.atmosphere.ai.intent.IntentRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>A turn that is cancelled — the client disconnected, or the dispatching
 * thread was interrupted (a batch item cancelled by its job) — is never
 * routed: the session errors with a {@link CancellationException} and no
 * handler or human route runs for a requester who is gone. The interrupt
 * status is preserved.</p>
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
     * @param message   the text to classify: the user message as admission (guardrails,
     *                  policies, interceptors, per-request scope) left it, before any
     *                  RAG augmentation
     * @param rawMessage the message as the caller received it, recorded in memory
     *                  exactly like the LLM path's memory layer does
     * @param request   the admitted request
     * @param base      the session the turn writes to
     * @param memory    conversation memory, or {@code null}
     * @param memoryKey the key the memory layer stores under
     * @param approvals the approval registry a CONFIRM-tier confirmation is asked
     *                  on, or {@code null} when the surface has no channel for the
     *                  requester's answer (OpenAI-compatible and batch serving):
     *                  CONFIRM then escalates at once instead of waiting
     * @param cancelled whether the turn was cancelled (client disconnect); an
     *                  interrupted dispatching thread counts as cancelled too
     */
    record Step(IntentRouting routing, String message, String rawMessage, AiRequest request,
                StreamingSession base, AiConversationMemory memory, String memoryKey,
                ApprovalRegistry approvals, BooleanSupplier cancelled) {
    }

    static Outcome route(Step step) {
        var routing = step.routing();
        var metadata = step.request().metadata();
        if (metadata != null && metadata.containsKey(ScopePolicy.REDIRECT_METADATA_KEY)) {
            // The scope policy already decided this turn: the request was
            // rewritten to its redirect text, which the LLM path renders.
            // Classifying the redirect text could hand an out-of-scope request
            // to a deterministic handler, so it is not classified at all.
            logger.debug("Intent routing skipped: the request was redirected by its scope policy");
            return Outcome.CONTINUE_TO_LLM;
        }
        if (cancelled(step)) {
            return cancel(step, "before classification");
        }
        var classification = routing.classify(step.message());
        if (cancelled(step)) {
            // The requester left (or the turn was cancelled) while the model
            // was classifying: nobody is routed, let alone escalated.
            return cancel(step, "during classification");
        }
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
                    return cancel(step, "before the requester answered the confirmation");
                }
                if (confirmed == Confirmation.APPROVED) {
                    route = candidate;
                    reason = reason + "; confirmed by the requester";
                } else if (confirmed == Confirmation.UNAVAILABLE) {
                    reason = reason + "; confirmation unavailable on this surface";
                } else {
                    reason = reason + "; confirmation " + confirmed.name().toLowerCase(Locale.ROOT);
                }
            }
        }

        if (cancelled(step)) {
            return cancel(step, "before the route ran");
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

    /**
     * Whether the turn is cancelled: the caller's latch (client disconnect) or
     * an interrupted dispatching thread. Reading the interrupt status does not
     * clear it.
     */
    private static boolean cancelled(Step step) {
        return (step.cancelled() != null && step.cancelled().getAsBoolean())
                || Thread.currentThread().isInterrupted();
    }

    /** Terminal path for a cancelled turn: error the session, run no route. */
    private static Outcome cancel(Step step, String when) {
        logger.debug("Intent routing cancelled {}", when);
        step.base().error(new CancellationException("intent routing cancelled " + when));
        return Outcome.HANDLED;
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

    private enum Confirmation { APPROVED, DENIED, TIMED_OUT, UNAVAILABLE, CANCELLED }

    private static Confirmation confirm(Step step, IntentRoute candidate, AiConfidence confidence) {
        var registry = step.approvals();
        if (registry == null) {
            // No channel to ask on: an unconfirmed choice never acts, and
            // nothing is parked waiting for an answer that cannot arrive.
            return Confirmation.UNAVAILABLE;
        }
        // A disconnect that came before the approval exists has nothing to
        // cancel (cancelAllPending ran on an empty registry): never park on it.
        if (cancelled(step)) {
            return Confirmation.CANCELLED;
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
        // Register before emitting, so an answer that races the event resolves.
        var future = registry.registerForResolution(approval);
        if (cancelled(step)) {
            // Closes the race with a concurrent cancelAllPending() that ran
            // between the check above and the registration: withdraw our own
            // approval instead of parking until it expires.
            registry.resolve(ApprovalRegistry.APPROVAL_PREFIX + approval.approvalId() + "/deny");
            return Confirmation.CANCELLED;
        }
        var expiresIn = Duration.between(Instant.now(), approval.expiresAt()).toSeconds();
        step.base().emit(new AiEvent.ApprovalRequired(approval.approvalId(), approval.toolName(),
                approval.arguments(), approval.message(), expiresIn));
        ApprovalStrategy.ApprovalOutcome outcome;
        try {
            outcome = registry.awaitResolution(approval, future).outcome();
        } catch (ApprovalRegistry.ApprovalTimeoutException e) {
            outcome = ApprovalStrategy.ApprovalOutcome.TIMED_OUT;
        } catch (RuntimeException e) {
            if (interrupted(e)) {
                // The registry wraps the InterruptedException and clears the
                // flag: restore it, and treat the wait as cancelled, not as
                // the requester's denial.
                Thread.currentThread().interrupt();
                return Confirmation.CANCELLED;
            }
            logger.warn("Intent confirmation for '{}' failed; escalating", candidate.name(), e);
            outcome = ApprovalStrategy.ApprovalOutcome.DENIED;
        }
        if (outcome == ApprovalStrategy.ApprovalOutcome.APPROVED) {
            return Confirmation.APPROVED;
        }
        // A disconnect cancels pending approvals as denials; it is not the
        // requester's answer, so nobody is escalated for a client that left.
        if (cancelled(step)) {
            return Confirmation.CANCELLED;
        }
        return outcome == ApprovalStrategy.ApprovalOutcome.TIMED_OUT
                ? Confirmation.TIMED_OUT : Confirmation.DENIED;
    }

    private static boolean interrupted(Throwable failure) {
        for (var t = failure; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The routing for this request: request metadata wins over the default.
     * The metadata entry is consumed by the caller so it never reaches the provider.
     */
    static Optional<IntentRouting> resolve(Map<String, Object> metadata, IntentRouting fallback) {
        var fromRequest = IntentRouting.from(metadata);
        return Optional.ofNullable(fromRequest != null ? fromRequest : fallback);
    }
}
