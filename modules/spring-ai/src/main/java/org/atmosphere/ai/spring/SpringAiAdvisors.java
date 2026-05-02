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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AgentExecutionContext;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper for attaching per-request Spring AI {@link Advisor} instances to an
 * {@link AgentExecutionContext} and reading them back inside
 * {@link SpringAiAgentRuntime}.
 *
 * <p>Spring AI's advisor chain is the framework's main extension point — it is
 * how RAG ({@code QuestionAnswerAdvisor} / {@code RetrievalAugmentationAdvisor}),
 * memory ({@code MessageChatMemoryAdvisor} / {@code PromptChatMemoryAdvisor}),
 * guardrails ({@code SafeGuardAdvisor}), and observability
 * ({@code SimpleLoggerAdvisor}) plug into a {@code ChatClient} call. Without
 * this bridge an Atmosphere caller had to pick one mental model: drive
 * everything through the Atmosphere pipeline, or hand-build a Spring AI
 * advisor chain and skip Atmosphere streaming. This helper lets both layers
 * coexist on the same request.</p>
 *
 * <h2>Wire scope</h2>
 *
 * <p>Two scopes already exist for advisors before this helper:</p>
 * <ul>
 *   <li><b>{@code ChatClient.Builder.defaultAdvisors(...)}</b> — installed at
 *       bean construction. Every request through that builder inherits them.
 *       Atmosphere does <em>not</em> need to do anything for these to fire;
 *       Spring AI handles the inheritance internally.</li>
 *   <li><b>{@code ChatClient.prompt().advisors(...)}</b> — installed per
 *       request and <em>appended</em> to the builder defaults. This is the
 *       slot this helper drives, so callers can pass advisors that live for
 *       a single Atmosphere call (e.g. an audit advisor scoped to one user
 *       session) without mutating the shared {@code ChatClient.Builder}.</li>
 * </ul>
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * <p>The list rides on {@link AgentExecutionContext#metadata()} under
 * {@link #METADATA_KEY} rather than on a typed record field, mirroring the
 * {@link org.atmosphere.ai.llm.CacheHint} convention. This keeps
 * {@code modules/ai} free of any {@code spring-ai-client-chat} dependency —
 * the {@code Advisor} type is only resolved inside the {@code spring-ai}
 * module where it is already provided.</p>
 *
 * <p>The cast inside {@link #from(AgentExecutionContext)} is checked
 * element-wise: a non-{@code Advisor} value in the slot is logged through
 * normal {@code ClassCastException} rather than silently dropped, so
 * misconfiguration shows up loudly rather than degrading to a no-op.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * var safeGuard = SafeGuardAdvisor.builder()
 *         .sensitiveWords(List.of("badword"))
 *         .failureResponse("I cannot answer that.")
 *         .build();
 *
 * var ctx = SpringAiAdvisors.attach(baseContext, safeGuard, new SimpleLoggerAdvisor());
 * runtime.execute(ctx, session);
 * }</pre>
 */
public final class SpringAiAdvisors {

    /**
     * Canonical metadata slot. The runtime reads from this key only; callers
     * who need to bypass {@link #attach} (e.g. when mutating an existing
     * metadata map) must use the same string.
     */
    public static final String METADATA_KEY = "spring-ai.advisors";

    private SpringAiAdvisors() {
    }

    /**
     * Read the per-request advisor list out of {@code context.metadata()}.
     * Returns an empty list when no slot is present, when the slot is
     * {@code null}, when it is an empty collection, or when it is not a
     * {@link List}. Element-level type errors throw — silent drops on a
     * misconfigured advisor would mask guardrail / RAG bypass bugs.
     */
    @SuppressWarnings("unchecked")
    public static List<Advisor> from(AgentExecutionContext context) {
        if (context == null || context.metadata() == null) {
            return List.of();
        }
        var slot = context.metadata().get(METADATA_KEY);
        if (slot == null) {
            return List.of();
        }
        if (!(slot instanceof List<?> raw)) {
            throw new IllegalArgumentException(
                    METADATA_KEY + " must be a List<Advisor>, got "
                            + slot.getClass().getName());
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        for (var element : raw) {
            if (element != null && !(element instanceof Advisor)) {
                throw new ClassCastException(
                        METADATA_KEY + " element is not a Spring AI Advisor: "
                                + element.getClass().getName());
            }
        }
        return List.copyOf((List<Advisor>) raw);
    }

    /**
     * Return a new context with {@code advisors} attached under
     * {@link #METADATA_KEY}. If the original context already carries a slot,
     * the supplied advisors are <em>appended</em> to the existing list — this
     * matches Spring AI's own additive semantics where
     * {@code prompt().advisors(...)} extends the builder defaults rather than
     * replacing them.
     *
     * @param context the source context (must not be {@code null})
     * @param advisors per-request advisors; {@code null} entries are rejected
     */
    public static AgentExecutionContext attach(AgentExecutionContext context, Advisor... advisors) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (advisors == null || advisors.length == 0) {
            return context;
        }
        // Use Arrays.asList rather than List.of so the null-entry check inside
        // attach(context, List) reports IllegalArgumentException with our
        // diagnostic — List.of() throws NPE before the null check can fire.
        return attach(context, java.util.Arrays.asList(advisors));
    }

    /**
     * List-typed overload of {@link #attach(AgentExecutionContext, Advisor...)}.
     */
    public static AgentExecutionContext attach(AgentExecutionContext context, List<Advisor> advisors) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (advisors == null || advisors.isEmpty()) {
            return context;
        }
        for (var advisor : advisors) {
            if (advisor == null) {
                throw new IllegalArgumentException("advisors list contains a null entry");
            }
        }

        var existing = from(context);
        var merged = new java.util.ArrayList<Advisor>(existing.size() + advisors.size());
        merged.addAll(existing);
        merged.addAll(advisors);

        Map<String, Object> nextMetadata = new HashMap<>(context.metadata());
        nextMetadata.put(METADATA_KEY, List.copyOf(merged));
        return context.withMetadata(Map.copyOf(nextMetadata));
    }
}
