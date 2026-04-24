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
package org.atmosphere.ai.governance.rag;

import org.atmosphere.ai.ContextProvider;

/**
 * Strategy for flagging indirect prompt-injection attempts embedded in RAG
 * documents, tool outputs, and web content the agent ingests. One
 * implementation per {@link Tier} — same shape as
 * {@link org.atmosphere.ai.governance.scope.ScopeGuardrail}, same
 * cross-provider invariant: {@link Tier#EMBEDDING_SIMILARITY} and
 * {@link Tier#LLM_CLASSIFIER} both consume the installed
 * {@link org.atmosphere.ai.EmbeddingRuntime} / {@link org.atmosphere.ai.AgentRuntime}
 * respectively, so every runtime adapter (Built-in, Spring AI, LangChain4j,
 * ADK, Embabel, Koog, Semantic Kernel) participates in classification
 * without a per-runtime rewrite.
 *
 * <p>Addresses <b>OWASP Agentic Top-10 A04 Indirect Prompt Injection</b>.
 * The {@link SafetyContextProvider} decorator wraps any user-declared
 * {@link ContextProvider} and drops / flags / sanitizes documents whose
 * score exceeds the configured threshold.</p>
 *
 * <p>Implementations MUST be thread-safe and MUST NOT throw — exceptions
 * are caught at the caller and treated as {@link Outcome#ERROR}; the
 * wrapping {@link SafetyContextProvider} then applies the operator's
 * breach policy (drop by default, matching the fail-closed contract of
 * Correctness Invariant #2).</p>
 */
public interface InjectionClassifier {

    /** Which tier this implementation handles; returned by {@link #tier()}. */
    Tier tier();

    /**
     * Classify one retrieved document.
     *
     * @param document the document as returned by the wrapped
     *                 {@link ContextProvider}; never {@code null}
     * @return the verdict plus audit fields used by the decorator to build
     *         the drop / flag message
     */
    Decision evaluate(ContextProvider.Document document);

    /** Tiered implementation strategy matching the {@code @AgentScope.Tier} pattern. */
    enum Tier {
        /**
         * Keyword / regex match against canonical injection vectors
         * ("ignore previous instructions", "system prompt:", role reversal,
         * jailbreak templates). Sub-millisecond, zero dependencies. Brittle
         * on creative phrasings but a safe fallback when no runtime / no
         * embedding model is available.
         */
        RULE_BASED,

        /**
         * Cosine-similarity against a cached set of known injection
         * exemplars, computed via
         * {@link org.atmosphere.ai.EmbeddingRuntime}. Catches paraphrases
         * the rule-based tier misses. Adds one embedding round-trip per
         * retrieved document — amortized by the typical 3-5 docs per turn.
         */
        EMBEDDING_SIMILARITY,

        /**
         * Zero-shot LLM classifier via
         * {@link org.atmosphere.ai.AgentRuntime}. Highest recall on novel
         * payloads at the cost of one LLM round-trip per document —
         * operators opt in for high-stakes RAG (medical / legal corpora).
         */
        LLM_CLASSIFIER
    }

    /** Result of a classification — verdict plus telemetry. */
    record Decision(Outcome outcome, String reason, double confidence) {

        public Decision {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
            reason = reason == null ? "" : reason;
        }

        public static Decision safe(double confidence) {
            return new Decision(Outcome.SAFE, "", confidence);
        }

        public static Decision injected(String reason, double confidence) {
            return new Decision(Outcome.INJECTED, reason, confidence);
        }

        public static Decision error(String reason) {
            return new Decision(Outcome.ERROR, reason, Double.NaN);
        }
    }

    enum Outcome {
        SAFE,
        INJECTED,
        ERROR
    }
}
