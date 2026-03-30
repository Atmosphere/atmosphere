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
package org.atmosphere.ai.test;

/**
 * Strategy for LLM-as-judge evaluation. Defines how the judge LLM is prompted
 * and how its response is parsed into scores.
 */
public interface EvalStrategy {

    /**
     * Build the judge prompt for intent evaluation.
     *
     * @param userMessage the original user message
     * @param agentResponse the agent's response text
     * @param intent the expected intent
     * @return the judge prompt
     */
    String buildIntentPrompt(String userMessage, String agentResponse, String intent);

    /**
     * Build the judge prompt for grounding evaluation.
     *
     * @param agentResponse the agent's response text
     * @param toolOutputs the tool outputs the response should be grounded in
     * @return the judge prompt
     */
    String buildGroundingPrompt(String agentResponse, String toolOutputs);

    /**
     * Build the judge prompt for quality evaluation.
     *
     * @param userMessage the original user message
     * @param agentResponse the agent's response text
     * @return the judge prompt
     */
    String buildQualityPrompt(String userMessage, String agentResponse);

    /**
     * Parse a boolean verdict from the judge's response.
     * Expected format: JSON with a "verdict" field (true/false).
     *
     * @param judgeResponse the raw judge response
     * @return true if the judge approves
     */
    boolean parseVerdict(String judgeResponse);

    /**
     * Parse quality scores from the judge's response.
     * Expected format: JSON with "relevance", "coherence", "safety" fields (0.0-1.0).
     *
     * @param judgeResponse the raw judge response
     * @return parsed scores
     */
    QualityScores parseQualityScores(String judgeResponse);

    /**
     * Quality scores returned by the judge.
     */
    record QualityScores(double relevance, double coherence, double safety) {}

    /**
     * Default strategy using structured JSON prompts.
     */
    static EvalStrategy defaultStrategy() {
        return new DefaultEvalStrategy();
    }
}
