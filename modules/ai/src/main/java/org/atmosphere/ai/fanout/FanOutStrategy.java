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
package org.atmosphere.ai.fanout;

/**
 * Strategy for handling multiple concurrent model responses in a fan-out session.
 *
 * <ul>
 *   <li>{@link AllResponses} — stream all model responses to the client in parallel;
 *       the client (or application) selects the preferred one.</li>
 *   <li>{@link FirstComplete} — use the first model to finish; cancel the others.</li>
 *   <li>{@link FastestTokens} — use the model producing tokens fastest after
 *       an initial observation window; cancel the others.</li>
 * </ul>
 */
public sealed interface FanOutStrategy
        permits FanOutStrategy.AllResponses,
                FanOutStrategy.FirstComplete,
                FanOutStrategy.FastestTokens {

    /**
     * Stream all model responses to the client in parallel. Each model's tokens
     * arrive on a separate child session ID so the client can distinguish them.
     */
    record AllResponses() implements FanOutStrategy {}

    /**
     * Use the first model to complete its response. All other in-flight model
     * calls are cancelled once one finishes.
     */
    record FirstComplete() implements FanOutStrategy {}

    /**
     * Observe token production speed for a configurable number of initial tokens,
     * then keep the fastest model and cancel the rest.
     *
     * @param tokenThreshold number of tokens to observe before choosing a winner
     */
    record FastestTokens(int tokenThreshold) implements FanOutStrategy {}
}
