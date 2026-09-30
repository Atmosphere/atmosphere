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

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Routes every completed turn on its {@link AiConfidence}: the answer says
 * <em>what</em>, the confidence says <em>whether to act on it</em>.
 *
 * <p>When a routing is in scope, the shared dispatch composer installs a
 * {@code ConfidenceRoutingSession} on both dispatch paths ({@code @AiEndpoint}
 * and {@link AiPipeline}). On a successful completion it resolves a
 * {@link ConfidenceRoute}, emits it as the {@value #ROUTE_METADATA_KEY} wire
 * signal and hands a {@link ConfidenceDecision} to {@link #onDecision()} —
 * both before the terminal frame. A turn that errors, or that a guardrail
 * already blocked, is not routed: there is no answer to act on.</p>
 *
 * <h2>Where the confidence comes from</h2>
 * The router consumes whatever the turn reported through
 * {@link StreamingSession#confidence(AiConfidence)}: native logprobs from a
 * runtime that exposes them, otherwise the model-reported field. For a
 * structured response, designate the decision with
 * {@link AiConfidenceElicitation#withDecisionField(String)} so the Built-in
 * runtime routes on {@link AiConfidence.Source#DECISION_LOGPROBS} — how sure
 * the model was of that value — rather than on the fluency of the whole
 * text. A routing
 * with no {@link AiConfidenceElicitation} in scope installs
 * {@link AiConfidenceElicitation#defaults()} so a signal exists at all. With a
 * structured response type, the elicitation cue is not appended (it would
 * break the single-JSON-object parse) — declare the confidence field on the
 * response record instead; the router reads it from the raw JSON.
 *
 * <h2>Fail closed</h2>
 * A turn with no usable confidence — the model ignored the cue, the value was
 * out of range, the runtime emitted nothing — routes to {@link #unknownRoute()},
 * which defaults to {@link ConfidenceRoute#ESCALATE}. Unknown is never
 * treated as confident.
 *
 * @param actAt        minimum confidence for {@link ConfidenceRoute#ACT}
 * @param confirmAt    minimum confidence for {@link ConfidenceRoute#CONFIRM};
 *                     below it the turn routes to {@link ConfidenceRoute#ESCALATE}
 * @param unknownRoute route for a turn without a usable confidence
 * @param onDecision   receives each decision, or {@code null} to rely on the
 *                     wire signal alone; called on the thread completing the
 *                     turn, so it must not block
 */
public record ConfidenceRouting(double actAt, double confirmAt, ConfidenceRoute unknownRoute,
                                Consumer<ConfidenceDecision> onDecision) {

    /** Metadata key for threading a per-request routing through the pipeline. */
    public static final String METADATA_KEY = "ai.confidence.routing";

    /** Wire metadata key carrying the resolved {@link ConfidenceRoute} name. */
    public static final String ROUTE_METADATA_KEY = "ai.confidence.route";

    /** Default act threshold. */
    public static final double DEFAULT_ACT_AT = 0.9;

    /** Default confirm threshold. */
    public static final double DEFAULT_CONFIRM_AT = 0.5;

    public ConfidenceRouting {
        if (!(confirmAt >= 0.0 && confirmAt <= actAt && actAt <= 1.0)) {
            throw new IllegalArgumentException(
                    "thresholds must satisfy 0 <= confirmAt <= actAt <= 1, got confirmAt="
                            + confirmAt + ", actAt=" + actAt);
        }
        Objects.requireNonNull(unknownRoute, "unknownRoute");
    }

    /** Act at 0.9 and above, confirm from 0.5, escalate below and on unknown. */
    public static ConfidenceRouting defaults() {
        return new ConfidenceRouting(DEFAULT_ACT_AT, DEFAULT_CONFIRM_AT, ConfidenceRoute.ESCALATE, null);
    }

    /** Custom thresholds, escalating on unknown. */
    public static ConfidenceRouting of(double actAt, double confirmAt) {
        return new ConfidenceRouting(actAt, confirmAt, ConfidenceRoute.ESCALATE, null);
    }

    /** Same thresholds, with a decision handler. */
    public ConfidenceRouting withHandler(Consumer<ConfidenceDecision> handler) {
        return new ConfidenceRouting(actAt, confirmAt, unknownRoute, handler);
    }

    /**
     * Same thresholds, with a different route for an unknown signal. Anything
     * other than {@link ConfidenceRoute#ESCALATE} lets a turn nobody measured
     * proceed — choose it deliberately.
     */
    public ConfidenceRouting withUnknownRoute(ConfidenceRoute route) {
        return new ConfidenceRouting(actAt, confirmAt, route, onDecision);
    }

    /** Resolve the route for a confidence signal; {@code null} counts as unknown. */
    public ConfidenceRoute route(AiConfidence confidence) {
        if (confidence == null || confidence.aggregate().isEmpty()) {
            return unknownRoute;
        }
        var value = confidence.aggregate().getAsDouble();
        if (value >= actAt) {
            return ConfidenceRoute.ACT;
        }
        return value >= confirmAt ? ConfidenceRoute.CONFIRM : ConfidenceRoute.ESCALATE;
    }

    /** Extract a routing from request metadata; {@code null} when absent. */
    public static ConfidenceRouting from(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.get(METADATA_KEY) instanceof ConfidenceRouting r ? r : null;
    }
}
