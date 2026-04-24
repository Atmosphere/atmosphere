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

import org.atmosphere.ai.annotation.AgentScope;

/**
 * Telemetry facade for governance-plane observables that don't fit the
 * general {@code AiMetrics} surface (cost, latency, tool calls). Two signals
 * today:
 *
 * <ul>
 *   <li><b>Similarity score histogram</b> — distribution of
 *       {@link org.atmosphere.ai.governance.scope.ScopeGuardrail.Decision#similarity}
 *       values per scope policy and tier, sliced by admit / deny / error.
 *       Operators use this to calibrate {@code similarityThreshold} — if the
 *       admit-vs-deny bands overlap badly, the threshold is wrong.</li>
 *   <li><b>Evaluation latency timer</b> — per-policy {@code evaluate()}
 *       elapsed time, so operators can tell at a glance when a tier switch
 *       (e.g. rule-based → LLM-classifier) changes admission-path cost.</li>
 * </ul>
 *
 * <p>Installed via {@link GovernanceMetricsHolder}; default NOOP. Micrometer
 * implementation ships in {@code atmosphere-spring-boot-starter} /
 * {@code atmosphere-quarkus-extension} via auto-configuration; the admin
 * console's OWASP + Decisions views pull the histogram summary through the
 * {@code /api/admin/governance/metrics} endpoint.</p>
 */
public interface GovernanceMetrics {

    /** No-op default — recorded into /dev/null when no metrics backend is wired. */
    GovernanceMetrics NOOP = new GovernanceMetrics() {
        @Override public void recordSimilarity(String policyName, AgentScope.Tier tier,
                                                String decision, double similarity) { }
        @Override public void recordEvaluationLatency(String policyName, String decision,
                                                      double evaluationMs) { }
    };

    /**
     * Record one similarity score. Implementations typically bucket into a
     * distribution summary tagged with {@code policy}, {@code tier}, and
     * {@code decision}.
     *
     * @param policyName  the policy's {@code name()} value; never blank
     * @param tier        the guardrail tier that produced the score
     * @param decision    one of {@code admit}, {@code deny}, {@code transform}, {@code error}
     * @param similarity  similarity value in [0, 1] or {@code NaN} for errors
     */
    void recordSimilarity(String policyName, AgentScope.Tier tier,
                          String decision, double similarity);

    /**
     * Record one evaluation latency (milliseconds).
     *
     * @param policyName    the policy's {@code name()} value
     * @param decision      one of {@code admit}, {@code deny}, {@code transform}, {@code error}
     * @param evaluationMs  elapsed time in milliseconds
     */
    void recordEvaluationLatency(String policyName, String decision, double evaluationMs);
}
