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
package org.atmosphere.coordinator.evaluation;

import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentResult;

/**
 * SPI for post-execution quality assessment of agent results. Implementations
 * are discovered via {@link java.util.ServiceLoader} and invoked both
 * automatically (async, recorded in journal) and explicitly via
 * {@code AgentFleet.evaluate()}.
 *
 * <p>Evaluators run outside the streaming hot path and should be safe to call
 * synchronously. For LLM-based evaluation, implementations should manage
 * their own client lifecycle.</p>
 */
public interface ResultEvaluator {

    /**
     * Evaluate an agent result against the original call context.
     *
     * @param result       the result returned by the agent
     * @param originalCall the call that produced this result
     * @return an evaluation with score, pass/fail, and explanation
     */
    Evaluation evaluate(AgentResult result, AgentCall originalCall);

    /** Human-readable name of this evaluator. */
    default String name() {
        return getClass().getSimpleName();
    }
}
