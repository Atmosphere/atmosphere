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
package org.atmosphere.ai.decision;

/**
 * A model that answers typed questions about a piece of state instead of
 * generating text: pick one option ({@link Question.Choice}), place the state
 * on an ordered rubric ({@link Question.Score}), or decide a boolean
 * ({@link Question.Noul}). Every question of a {@link DecisionRequest} is
 * evaluated in isolation (no question sees another's text) and in parallel.
 *
 * <p>This is deliberately a separate SPI from
 * {@link org.atmosphere.ai.AgentRuntime}: nothing streams, and the output is a
 * typed answer with a confidence, not text. {@link RuntimeDecisionModel} is the
 * reference implementation over any {@code AgentRuntime}; other
 * implementations register through
 * {@code META-INF/services/org.atmosphere.ai.decision.DecisionModel} and are
 * picked by {@link DecisionModelResolver}.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #decide} returns within {@link DecisionRequest#timeout()} plus a
 *       small scheduling slack, whatever the backend does.</li>
 *   <li>Every requested question id is present in
 *       {@link DecisionResult#answers()}. A question that could not be answered
 *       is an {@link Answer.Failed}, never a missing entry and never a guess.</li>
 *   <li>{@code decide} throws only {@link IllegalArgumentException} (an invalid
 *       request). Backend failures are per-question {@link Answer.Failed}.</li>
 *   <li>{@link Answer#confidence()} reports how the value was derived through
 *       {@link org.atmosphere.ai.AiConfidence#source()}; a probability map is
 *       filled only from a distribution that was actually observed.</li>
 * </ul>
 */
public interface DecisionModel {

    /** Human-readable name, used in logs and audit reasons. */
    String name();

    /**
     * Whether this model can answer right now. Runtime truth, never classpath
     * presence: an implementation whose backend is a canned fallback reports
     * {@code false}.
     */
    boolean isAvailable();

    /** Selection priority when several implementations are available; higher wins. */
    default int priority() {
        return 0;
    }

    /**
     * Answer every question of {@code request}. Each requested id resolves to
     * exactly one {@link Answer} before {@link DecisionRequest#timeout()} (plus
     * a small slack); per-question failures become {@link Answer.Failed}.
     *
     * @param request the state and the questions to answer about it
     * @return one answer per requested id, in request order
     * @throws IllegalArgumentException only for an invalid request
     */
    DecisionResult decide(DecisionRequest request);
}
