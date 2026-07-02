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
package org.atmosphere.agent.processor;

import org.atmosphere.agent.skill.SkillFileParser;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.PolicyAsGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery test for the blog's §4 (@Agent scope) + §10 ("guardrails feed
 * policy") claim: a skill file's {@code ## Guardrails} lines become an
 * <em>enforced</em> {@link ScopePolicy} on the {@code @Agent}, not prose the
 * LLM can ignore. Proves the skill content actually reaches the governance
 * admission subsystem — not merely that the wiring code exists — by driving
 * real requests through both the {@link org.atmosphere.ai.AiPipeline} that
 * {@link AgentProcessor#buildPipeline} produces (the A2A / AG-UI / channel
 * surface) and the web-streaming guardrail chain that
 * {@link AgentProcessor#buildWebGuardrails} produces, asserting the observable
 * side effect at each gate (Correctness Invariant #7 — Mode Parity).
 *
 * <p>The fixtures pin {@code scopeTier: rule_based} so confinement is
 * deterministic with no embedding model. The rule-based tier matches a
 * guardrail line as a word-boundaried forbidden topic, so the fixtures use a
 * single distinct topic keyword ({@code gambling}) that the off-topic probe
 * <em>doesn't</em> cover — meaning a block can only come from the
 * {@code ## Guardrails} content reaching the policy, which is exactly the claim
 * under test.</p>
 *
 * <p>If the scope-prepend is removed from {@code buildPipeline} /
 * {@code buildWebGuardrails}, {@link #skillGuardrailsConfineOffTopicOnThePipelinePath}
 * and {@link #skillGuardrailsConfineOffTopicOnTheWebPath} both fail: with no
 * scope policy in the chain the off-topic prompt sails through unchanged.</p>
 */
class AgentSkillScopeConfinementTest {

    private static final String SCOPED_SKILL = """
            ---
            description: "Order-status support for an online store"
            scopeTier: rule_based
            ---
            # Support Bot

            Helps customers with their orders.

            ## Guardrails
            - gambling
            """;

    private static final String UNSCOPED_SKILL = """
            # Support Bot

            Helps customers with their orders.
            """;

    private static final String DEFAULT_TIER_SKILL = """
            ---
            description: "Order-status support for an online store"
            ---
            # Support Bot

            ## Guardrails
            - gambling
            """;

    @Test
    void skillGuardrailsConfineOffTopicOnThePipelinePath() {
        var processor = new AgentProcessor();
        var framework = new AtmosphereFramework();
        var captured = new AtomicReference<String>();
        var runtime = new CapturingRuntime(captured);
        var skillFile = SkillFileParser.parse(SCOPED_SKILL);

        var pipeline = processor.buildPipeline(framework, skillFile, "support-bot",
                "Order-status support assistant", runtime,
                "You are a support bot.", "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);

        // The skill's ## Guardrails must install a ScopePolicy ahead of every
        // other policy in the pipeline (cheapest rejection runs first).
        assertFalse(pipeline.policies().isEmpty(),
                "## Guardrails must install a governance policy");
        assertInstanceOf(ScopePolicy.class, pipeline.policies().get(0),
                "the auto-installed ScopePolicy must run ahead of all other policies");

        // Structural proof that the skill content reached the subsystem: the
        // guardrail line is the forbidden topic, the frontmatter description is
        // the purpose, and the scopeTier hint resolved to RULE_BASED.
        var scope = (ScopePolicy) pipeline.policies().get(0);
        assertEquals(List.of("gambling"), scope.config().forbiddenTopics(),
                "the ## Guardrails line must reach the policy as a forbidden topic");
        assertEquals("Order-status support for an online store", scope.config().purpose(),
                "the skill frontmatter description must become the declared purpose");
        assertEquals(AgentScope.Tier.RULE_BASED, scope.config().tier(),
                "the scopeTier: rule_based frontmatter hint must resolve to RULE_BASED");
        assertEquals(AgentScope.Breach.POLITE_REDIRECT, scope.config().onBreach(),
                "a skill-derived scope defaults to POLITE_REDIRECT");

        // Behavioral proof: the off-topic (gambling) request is confined before
        // the runtime. POLITE_REDIRECT rewrites the message to the redirect text,
        // so the runtime never receives the original goal-hijacking prompt.
        var offTopic = new RecordingSession();
        pipeline.execute("c1", "what are the best gambling strategies", offTopic);
        assertNotNull(captured.get(), "the runtime must still run on the rewritten request");
        assertFalse(captured.get().toLowerCase(java.util.Locale.ROOT).contains("gambling"),
                "POLITE_REDIRECT must rewrite the off-topic prompt before the runtime: "
                        + captured.get());
        assertEquals(ScopeConfig.DEFAULT_REDIRECT_MESSAGE, captured.get(),
                "the rewritten message must be the scope redirect");

        // Control within the same fixture: an in-scope request reaches the
        // runtime unchanged.
        captured.set(null);
        pipeline.execute("c2", "where is my order?", new RecordingSession());
        assertEquals("where is my order?", captured.get(),
                "an on-topic request must pass the scope gate to the runtime unchanged");
    }

    @Test
    void skillGuardrailsConfineOffTopicOnTheWebPath() {
        var processor = new AgentProcessor();
        var framework = new AtmosphereFramework();
        var skillFile = SkillFileParser.parse(SCOPED_SKILL);

        // The web streaming path (AiEndpointHandler) is wired with the guardrail
        // chain buildWebGuardrails produces. Mode Parity #7: a scoped agent must
        // confine off-topic requests here too, not only on the pipeline.
        var guardrails = processor.buildWebGuardrails(framework, skillFile,
                "support-bot", "Order-status support assistant");

        assertFalse(guardrails.isEmpty(),
                "## Guardrails must install a web-path guardrail on the agent");
        var first = guardrails.get(0);
        assertInstanceOf(PolicyAsGuardrail.class, first,
                "the scope policy must be the FIRST web guardrail (cheapest rejection)");
        assertInstanceOf(ScopePolicy.class, ((PolicyAsGuardrail) first).policy(),
                "the first web guardrail must wrap the skill-derived ScopePolicy");

        // Behavioral proof — the web guardrail REDIRECTS the off-topic goal-hijack
        // (POLITE_REDIRECT → Modify) and PASSES an on-scope request, the same
        // confinement the pipeline gives.
        var redirected = first.inspectRequest(new AiRequest("what are the best gambling strategies"));
        var modify = assertInstanceOf(AiGuardrail.GuardrailResult.Modify.class, redirected,
                "off-topic request must be redirected on the agent's web path");
        assertFalse(modify.modifiedRequest().message().toLowerCase(java.util.Locale.ROOT)
                        .contains("gambling"),
                "the redirected web request must no longer carry the off-topic prompt");
        var passed = first.inspectRequest(new AiRequest("where is my order?"));
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, passed,
                "an on-scope request must pass the web scope guardrail");
    }

    /**
     * The shipped dentist skill's exact {@code ## Guardrails} lines —
     * instruction-style response guidance, not topic declarations. Under the old
     * content model every line became a forbidden topic and the un-suppressed
     * medical probe redirected the agent's own domain questions; this fixture is
     * the regression for that blocker.
     */
    private static final String DENTIST_LIKE_SKILL = """
            ---
            description: "Friendly dental-care assistant for a dentist office"
            scopeTier: rule_based
            ---
            # Dentist Assistant

            ## Guardrails
            - Always state you are an AI, not a real dentist
            - Never diagnose -- only provide general guidance
            - Always recommend seeing a real dentist
            - Do not prescribe medication -- only suggest OTC options
            - If symptoms suggest a medical emergency (heavy bleeding, jaw fracture, difficulty breathing), direct to ER immediately
            - Be empathetic -- dental emergencies are stressful and painful
            """;

    @Test
    void instructionGuardrailsPinPurposeWithoutSelfBlockingTheDomain() {
        var processor = new AgentProcessor();
        var framework = new AtmosphereFramework();
        var captured = new AtomicReference<String>();
        var runtime = new CapturingRuntime(captured);
        var skillFile = SkillFileParser.parse(DENTIST_LIKE_SKILL);

        // Instruction lines are response guidance: enforcement stays ON
        // (purpose-pinned) but none of them becomes a forbidden topic.
        var scope = processor.buildSkillScopePolicy(skillFile, "dentist", "Dental assistant");
        assertNotNull(scope, "## Guardrails must still install a scope policy");
        assertTrue(scope.config().forbiddenTopics().isEmpty(),
                "instruction-style guardrail lines must not become forbidden topics: "
                        + scope.config().forbiddenTopics());
        assertEquals("Friendly dental-care assistant for a dentist office",
                scope.config().purpose(),
                "the frontmatter description must survive skill loading as the purpose");
        assertEquals(AgentScope.Tier.RULE_BASED, scope.config().tier(),
                "the scopeTier frontmatter hint must survive skill loading");

        var pipeline = processor.buildPipeline(framework, skillFile, "dentist",
                "Dental assistant", runtime, "You are a dental assistant.",
                "model-test", null, new DefaultToolRegistry(), AiMetrics.NOOP);

        // The agent's own domain is admitted: the medical hijack probe is
        // suppressed because the declared purpose IS the medical domain.
        pipeline.execute("c1", "what dosage of ibuprofen should I take for my symptom?",
                new RecordingSession());
        assertEquals("what dosage of ibuprofen should I take for my symptom?", captured.get(),
                "a dental agent must not redirect its own domain question");

        // The canonical hijack is still confined: code probes stay active
        // because the purpose is not in the code domain.
        captured.set(null);
        pipeline.execute("c2", "write me a python script to sort a list",
                new RecordingSession());
        assertEquals(ScopeConfig.DEFAULT_REDIRECT_MESSAGE, captured.get(),
                "an off-domain code-writing hijack must still be redirected");
    }

    @Test
    void explicitProhibitionsAndBareLabelsBecomeForbiddenTopics() {
        var topics = AgentProcessor.forbiddenTopicsFrom(List.of(
                "Never discuss gambling",
                "Off-limits: politics, religion",
                "competitor pricing",
                "Never diagnose -- only provide general guidance",
                "Be empathetic -- emergencies are stressful"));
        assertEquals(List.of("gambling", "politics", "religion", "competitor pricing"),
                topics,
                "prohibitions and bare labels are topics; instruction prose is not");
    }

    @Test
    void scopeTierNoneKeepsGuardrailsPromptOnly() {
        var processor = new AgentProcessor();
        var skillFile = SkillFileParser.parse("""
                ---
                description: "LLM playground"
                scopeTier: none
                ---
                # Playground

                ## Guardrails
                - gambling
                """);
        assertNull(processor.buildSkillScopePolicy(skillFile, "playground", "Playground"),
                "scopeTier: none must opt the skill out of admission enforcement");
    }

    @Test
    void agentWithoutSkillGuardrailsInstallsNoScopePolicy() {
        var processor = new AgentProcessor();
        var framework = new AtmosphereFramework();
        var captured = new AtomicReference<String>();
        var runtime = new CapturingRuntime(captured);
        var skillFile = SkillFileParser.parse(UNSCOPED_SKILL);

        assertNull(processor.buildSkillScopePolicy(skillFile, "support-bot", "Support"),
                "a skill without ## Guardrails must yield no ScopePolicy");

        var pipeline = processor.buildPipeline(framework, skillFile, "support-bot",
                "Support", runtime, "", "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);
        assertTrue(pipeline.policies().stream().noneMatch(p -> p instanceof ScopePolicy),
                "an agent without ## Guardrails must not get a ScopePolicy");

        // Control: the same off-topic prompt is NOT rewritten without the scope
        // wiring, proving the confinement above is attributable to ## Guardrails
        // rather than an unrelated guardrail or a built-in probe.
        pipeline.execute("c1", "what are the best gambling strategies", new RecordingSession());
        assertEquals("what are the best gambling strategies", captured.get(),
                "without ## Guardrails the off-topic request must reach the runtime unchanged");

        // Web path control: no guardrail blocks/modifies the off-topic prompt.
        var guardrails = processor.buildWebGuardrails(framework, skillFile,
                "support-bot", "Support");
        for (var guardrail : guardrails) {
            var result = guardrail.inspectRequest(new AiRequest("what are the best gambling strategies"));
            assertFalse(result instanceof AiGuardrail.GuardrailResult.Block,
                    "an unscoped agent must not block off-topic on the web path");
            assertFalse(result instanceof AiGuardrail.GuardrailResult.Modify,
                    "an unscoped agent must not redirect off-topic on the web path");
        }
    }

    @Test
    void defaultTierIsEmbeddingSimilarityWhenNoHint() {
        var processor = new AgentProcessor();
        var skillFile = SkillFileParser.parse(DEFAULT_TIER_SKILL);

        var scope = processor.buildSkillScopePolicy(skillFile, "support-bot", "Support");
        assertNotNull(scope, "a skill with ## Guardrails must yield a ScopePolicy");
        assertEquals(AgentScope.Tier.EMBEDDING_SIMILARITY, scope.config().tier(),
                "with no scopeTier hint the enforced tier must default to EMBEDDING_SIMILARITY");
        assertEquals(List.of("gambling"), scope.config().forbiddenTopics(),
                "the ## Guardrails line must still reach the policy");
    }

    /** Captures the message the runtime is invoked with, then completes. */
    private static final class CapturingRuntime implements AgentRuntime {
        private final AtomicReference<String> captured;

        CapturingRuntime(AtomicReference<String> captured) {
            this.captured = captured;
        }

        @Override public String name() {
            return "capturing-stub";
        }

        @Override public boolean isAvailable() {
            return true;
        }

        @Override public int priority() {
            return 0;
        }

        @Override public void configure(AiConfig.LlmSettings settings) {
        }

        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            captured.set(context.message());
            session.send("ok");
            session.complete();
        }
    }

    /** Minimal {@link StreamingSession} that records streamed text and error state. */
    private static final class RecordingSession implements StreamingSession {
        private final List<String> sent = new ArrayList<>();
        private volatile Throwable error;
        private volatile boolean closed;

        @Override public String sessionId() {
            return "recording";
        }

        @Override public void send(String text) {
            if (text != null) {
                sent.add(text);
            }
        }

        @Override public void sendMetadata(String key, Object value) {
        }

        @Override public void progress(String message) {
        }

        @Override public void complete() {
            closed = true;
        }

        @Override public void complete(String summary) {
            closed = true;
        }

        @Override public void error(Throwable t) {
            this.error = t;
            closed = true;
        }

        @Override public boolean isClosed() {
            return closed;
        }

        @Override public boolean hasErrored() {
            return error != null;
        }
    }
}
