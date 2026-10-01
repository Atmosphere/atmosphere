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
package org.atmosphere.ai.intent;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.ConfidenceRoute;

import java.util.Objects;
import java.util.Optional;

/**
 * How one request was routed, handed to the route's {@link IntentHandler} and
 * to {@link IntentRouting#onDecision()}.
 *
 * @param route      the route the request took
 * @param choice     the route the decision model chose; empty when it gave no
 *                   usable answer (no model, timeout, failure)
 * @param tier       the confidence tier of the choice; {@link ConfidenceRoute#ESCALATE}
 *                   when there was no usable answer
 * @param confidence the confidence of the choice; unknown when there was none
 * @param reason     how the route was reached, for logs and audit
 * @param message    the text that was classified: the user message as the
 *                   request guardrails admitted it
 * @param request    the request being routed
 */
public record IntentDecision(String route, Optional<String> choice, ConfidenceRoute tier,
                             AiConfidence confidence, String reason, String message, AiRequest request) {

    public IntentDecision {
        Objects.requireNonNull(route, "route");
        choice = choice == null ? Optional.empty() : choice;
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(confidence, "confidence");
        reason = reason == null ? "" : reason;
        message = message == null ? "" : message;
        Objects.requireNonNull(request, "request");
    }
}
