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
     * Evaluate an agent result using all registered {@link ResultEvaluator}s.
     * Returns an empty list if no evaluators are registered.
     */
    default List<Evaluation> evaluate(AgentResult result, AgentCall originalCall) {
        return List.of();
    }

    /**
     * Access the coordination journal for querying past events.
     * Returns {@link CoordinationJournal#NOOP} if journaling is not active.
     */
    default CoordinationJournal journal() {
        return CoordinationJournal.NOOP;
    }
}
