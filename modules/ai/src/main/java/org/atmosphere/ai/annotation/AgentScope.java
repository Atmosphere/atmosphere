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
package org.atmosphere.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the allowed purpose of an {@link AiEndpoint} so the framework can
 * architecturally prevent goal hijacking — the failure mode in which a
 * general-purpose LLM helpfully answers off-topic requests (the McDonald's
 * support bot writing Python linked-list code, April 2026).
 *
 * <p>Prompt-engineered scope ("you are a customer support agent, only
 * answer about orders") is paper-thin. Any LLM will answer anything it can
 * unless something outside the prompt layer enforces confinement. This
 * annotation triggers three layers of enforcement at the {@code AiPipeline}
 * layer:</p>
 *
 * <ol>
 *   <li><b>Pre-admission gate</b> — a {@code ScopeGuardrail} inspects the
 *       request before it reaches the LLM and can deny / redirect.</li>
 *   <li><b>System-prompt hardening</b> — the framework prepends a
 *       scope-confinement block to the developer's system prompt.
 *       Unbypassable from sample code.</li>
 *   <li><b>Post-response check</b> (optional, per tier) — a classifier
 *       verifies the response stayed within scope; catches anything that
 *       slipped past the input check.</li>
 * </ol>
 *
 * <p>Sample-hygiene CI lint: every {@code @AiEndpoint} in {@code samples/}
 * must declare {@code @AgentScope} or explicitly opt out via
 * {@code @AgentScope(unrestricted = true, justification = "...")}. The
 * build fails if a sample endpoint is missing the annotation.</p>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AiEndpoint(path = "/atmosphere/support")
 * @AgentScope(
 *     purpose = "Customer support for Example Corp — orders, billing, account",
 *     forbiddenTopics = {"code", "programming", "medical", "legal"},
 *     onBreach = AgentScope.Breach.POLITE_REDIRECT,
 *     redirectMessage = "I can only help with Example Corp orders. What can I help you with?"
 * )
 * public class SupportChat {
 *     @Prompt
 *     public void onPrompt(String message, StreamingSession session) { … }
 * }
 * }</pre>
 *
 * <h2>OWASP mapping</h2>
 * Directly addresses <b>OWASP Agentic Top 10 #1 — Goal Hijacking</b>.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentScope {

    /**
     * Natural-language statement of the endpoint's allowed purpose. Flows into
     * the scope-confinement system-prompt block and, for the embedding tier,
     * is embedded once and cached as the reference vector.
     *
     * <p>Required unless {@link #unrestricted()} is true. Blank on an
     * {@code unrestricted = false} annotation is rejected at startup.</p>
     */
    String purpose() default "";

    /**
     * Topics explicitly disallowed within this scope. Rule-based tier uses
     * these as keyword triggers; embedding tier biases the reference vector
     * against them.
     */
    String[] forbiddenTopics() default {};

    /**
     * Behavior when a request breaches scope. Defaults to {@link Breach#POLITE_REDIRECT}.
     */
    Breach onBreach() default Breach.POLITE_REDIRECT;

    /**
     * User-visible message surfaced when {@link #onBreach()} is
     * {@link Breach#POLITE_REDIRECT} or {@link Breach#CUSTOM_MESSAGE}.
     * Defaults to a generic statement when empty.
     */
    String redirectMessage() default "";

    /**
     * Enforcement tier. Defaults to {@link Tier#EMBEDDING_SIMILARITY} —
     * deterministic, ~5–20 ms latency, the right floor for most endpoints.
     */
    Tier tier() default Tier.EMBEDDING_SIMILARITY;

    /**
     * Embedding-similarity threshold in {@code [0, 1]}. Requests whose
     * embedding similarity to the {@link #purpose()} vector falls below
     * this value are treated as out-of-scope. Only used when
     * {@link #tier()} is {@link Tier#EMBEDDING_SIMILARITY}. Defaults to
     * {@code 0.45} — empirically tuned for the canonical test set;
     * tighten for stricter scopes, loosen for broader ones.
     */
    double similarityThreshold() default 0.45;

    /**
     * Opt-out for samples or production endpoints that genuinely accept
     * arbitrary input (generic assistants, LLM playgrounds). When
     * {@code true}, {@link #justification()} is mandatory and
     * {@link #purpose()} / {@link #forbiddenTopics()} are ignored.
     *
     * <p>The sample-hygiene CI lint accepts {@code unrestricted = true}
     * iff a non-blank {@code justification} is present. Unjustified
     * opt-outs fail the build.</p>
     */
    boolean unrestricted() default false;

    /**
     * Required when {@link #unrestricted()} is true. Documents WHY the
     * endpoint rejects scope enforcement — reviewed by CI and by
     * anyone reading the source later.
     */
    String justification() default "";

    /** Defense posture when a request breaches scope. */
    enum Breach {
        /**
         * Respond with {@link AgentScope#redirectMessage()} (or a generic
         * message if blank) and close the turn cleanly. User sees a
         * helpful on-topic redirect. Default.
         */
        POLITE_REDIRECT,

        /**
         * Deny the turn with a {@link SecurityException}. The streaming
         * session surfaces the breach reason; no response text is sent.
         */
        DENY,

        /**
         * Emit {@link AgentScope#redirectMessage()} verbatim as the
         * user-visible response. Same wire behaviour as POLITE_REDIRECT
         * but semantically "this is the response" rather than "redirected."
         */
        CUSTOM_MESSAGE
    }

    /**
     * Scope enforcement strategy. All three tiers run the pre-admission
     * input check; {@link #POST_RESPONSE_CHECK} (enabled via
     * {@link AgentScope#postResponseCheck()}) adds a second pass after
     * the LLM replies.
     */
    enum Tier {
        /**
         * Keyword / regex matching over {@link AgentScope#forbiddenTopics()}
         * plus purpose-keyword negation. Sub-millisecond, brittle on
         * edge phrasings, best for clearly-delineated scopes.
         */
        RULE_BASED,

        /**
         * Compare the request embedding to the {@link AgentScope#purpose()}
         * embedding; reject when cosine similarity falls below
         * {@link AgentScope#similarityThreshold()}. Requires an
         * {@code EmbeddingRuntime}. Deterministic, ~5–20 ms latency.
         * <b>Default tier.</b>
         */
        EMBEDDING_SIMILARITY,

        /**
         * Call a small-model classifier to classify the request as
         * in-scope / out-of-scope. ~100–500 ms, most accurate, use for
         * high-stakes scopes where false-negatives cost more than
         * latency (medical, financial, legal-adjacent).
         */
        LLM_CLASSIFIER
    }

    /**
     * Enable a post-response classifier pass. Catches responses that
     * drifted out of scope despite clean admission. Off by default —
     * the pre-admission check covers most cases and this adds latency.
     */
    boolean postResponseCheck() default false;
}
