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
package org.atmosphere.ai.governance;

/**
 * Renders a governance decision into the single contrastive guidance line fed back to the
 * agent. Shared by both feedback paths so the ephemeral ({@link GovernanceFeedbackInterceptor}
 * re-injecting from {@link GovernanceDecisionLog}) and the durable
 * ({@link GovernanceMemorySink} persisting to long-term memory) paths render <em>identically</em>
 * — Correctness Invariant #7 (mode parity): the agent sees the same wording whether the guidance
 * came from the in-memory ring buffer or from a store that survived a restart.
 */
public final class GovernanceGuidance {

    private GovernanceGuidance() {
    }

    /**
     * The contrastive guidance line for a decision, or {@code null} when the decision is not
     * feedback-eligible. Only {@code "deny"} and {@code "prefer"} qualify — {@code admit},
     * {@code transform}, and {@code dry-run:*} shadow decisions do not steer the agent.
     *
     * @param decision  the recorded decision label ({@code deny} / {@code prefer} / …)
     * @param reason    the decision reason (may be blank)
     * @param preferred the preferred alternative (only meaningful for {@code prefer}; may be blank)
     */
    public static String line(String decision, String reason, String preferred) {
        var r = reason == null ? "" : reason.strip();
        return switch (decision == null ? "" : decision) {
            case "deny" -> r.isBlank()
                    ? "A prior action was denied by governance — do not repeat it."
                    : "A prior action was denied: " + r + " — do not repeat it.";
            case "prefer" -> {
                var p = preferred == null ? "" : preferred.strip();
                if (p.isBlank()) {
                    yield r.isBlank() ? null : "Preferred approach: " + r;
                }
                yield r.isBlank() ? "Prefer: " + p : "Prefer: " + p + " (" + r + ")";
            }
            default -> null;
        };
    }
}
