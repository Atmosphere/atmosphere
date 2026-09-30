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

import java.util.Objects;

/**
 * The routing outcome for one completed turn, handed to
 * {@link ConfidenceRouting#onDecision()}.
 *
 * @param route      what the application should do with the answer
 * @param confidence the signal the route was computed from; its
 *                   {@link AiConfidence#aggregate()} is empty when the turn
 *                   produced no usable confidence
 * @param request    the request that produced the turn
 */
public record ConfidenceDecision(ConfidenceRoute route, AiConfidence confidence, AiRequest request) {

    public ConfidenceDecision {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(request, "request");
    }
}
