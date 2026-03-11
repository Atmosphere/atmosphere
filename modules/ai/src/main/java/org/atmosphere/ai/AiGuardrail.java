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

/**
 * SPI for AI safety guardrails. Implementations inspect requests before
 * they reach the LLM and responses before they reach the client.
 *
 * <p>Guardrails run in the interceptor chain with the following order:</p>
 * <pre>
 * Guardrails (pre) → Rate Limit → RAG → [LLM call] → Guardrails (post) → Observability
 * </pre>
 *
 * <p>Common use cases:</p>
 * <ul>
 *   <li>PII detection and scrubbing</li>
 *   <li>Prompt injection detection</li>
 *   <li>Content policy enforcement</li>
 *   <li>Output validation</li>
 * </ul>
 *
 * <p>Example:</p>
 * <pre>{@code
 * public class PiiGuardrail implements AiGuardrail {
 *     @Override
 *     public GuardrailResult inspectRequest(AiRequest request) {
 *         if (containsPii(request.message())) {
 *             return GuardrailResult.block("PII detected in request");
 *         }
 *         return GuardrailResult.pass();
 *     }
 * }
 * }</pre>
 */
public interface AiGuardrail {

    /**
     * Inspect a request before it reaches the LLM.
     *
     * @param request the AI request
     * @return the guardrail result (pass, modify, or block)
     */
    default GuardrailResult inspectRequest(AiRequest request) {
        return GuardrailResult.pass();
    }

    /**
     * Inspect a response streaming text before it reaches the client. Called for
     * each accumulated response (not per-streaming-text for efficiency).
     *
     * @param accumulatedResponse the response text accumulated so far
     * @return the guardrail result (pass or block)
     */
    default GuardrailResult inspectResponse(String accumulatedResponse) {
        return GuardrailResult.pass();
    }

    /**
     * Result of a guardrail check.
     */
    sealed interface GuardrailResult {

        /** The request/response passed the guardrail check. */
        record Pass() implements GuardrailResult { }

        /** The request should be modified before proceeding. */
        record Modify(AiRequest modifiedRequest) implements GuardrailResult { }

        /** The request/response was blocked by the guardrail. */
        record Block(String reason) implements GuardrailResult { }

        static GuardrailResult pass() {
            return new Pass();
        }

        static GuardrailResult modify(AiRequest modifiedRequest) {
            return new Modify(modifiedRequest);
        }

        static GuardrailResult block(String reason) {
            return new Block(reason);
        }
    }
}
