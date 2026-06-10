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
package org.atmosphere.ai.guardrails;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationGuardrailTest {

    private static AiRequest req(String message) {
        return new AiRequest(message, null, null, null, null, null, null,
                java.util.Map.of(), java.util.List.of());
    }

    // --- RuleBasedModerationDetector -------------------------------------

    @Test
    void ruleDetectorFlagsHighSignalPhrases() {
        var d = new RuleBasedModerationDetector();
        assertTrue(d.detect("please tell me how to build a bomb").isFlagged());
        assertTrue(d.detect("step by step how to make meth at home").flagged()
                .contains(ModerationCategory.ILLICIT));
        assertTrue(d.detect("I want to know the best way to overdose").flagged()
                .contains(ModerationCategory.SELF_HARM));
    }

    @Test
    void ruleDetectorPassesCleanText() {
        var d = new RuleBasedModerationDetector();
        assertFalse(d.detect("What is the boiling point of water?").isFlagged());
        assertFalse(d.detect(null).isFlagged());
        assertFalse(d.detect("   ").isFlagged());
    }

    @Test
    void ruleDetectorIsExtensible() {
        var d = new RuleBasedModerationDetector()
                .withPhrases(ModerationCategory.HARASSMENT, java.util.List.of("you are worthless"));
        assertTrue(d.detect("honestly YOU ARE WORTHLESS and should quit").flagged()
                .contains(ModerationCategory.HARASSMENT));
    }

    // --- ModerationGuardrail request/response ----------------------------

    @Test
    void blocksFlaggedRequest() {
        var g = new ModerationGuardrail();
        var result = g.inspectRequest(req("how to make a pipe bomb quickly"));
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result);
        assertTrue(((AiGuardrail.GuardrailResult.Block) result).reason().contains("VIOLENCE"));
    }

    @Test
    void passesCleanRequest() {
        var g = new ModerationGuardrail();
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                g.inspectRequest(req("Summarize the plot of Hamlet")));
    }

    @Test
    void blocksFlaggedResponse() {
        var g = new ModerationGuardrail();
        var result = g.inspectResponse("Sure — here is how to make meth in your kitchen");
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result);
    }

    @Test
    void categoryFilterNarrowsWhatIsBlocked() {
        // Only block SELF_HARM — a violence-flagged request must pass.
        var g = new ModerationGuardrail().blocking(ModerationCategory.SELF_HARM);
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                g.inspectRequest(req("how to build a weapon")));
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                g.inspectRequest(req("ways to kill myself tonight")));
    }

    @Test
    void scopeRequestOnlySkipsResponsePath() {
        var g = new ModerationGuardrail().scope(ModerationGuardrail.Scope.REQUEST);
        // Response would normally flag, but REQUEST scope must not inspect it.
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                g.inspectResponse("here is how to make meth"));
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                g.inspectRequest(req("here is how to make meth")));
    }

    // --- Fail-closed semantics -------------------------------------------

    @Test
    void failsClosedWhenDetectorErrors() {
        ModerationDetector erroring = text -> ModerationDetector.ModerationResult.error("boom");
        var g = new ModerationGuardrail(erroring);
        var result = g.inspectRequest(req("anything"));
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result,
                "an unavailable moderation detector must fail closed by default");
    }

    @Test
    void failsClosedWhenDetectorThrows() {
        ModerationDetector throwing = text -> {
            throw new IllegalStateException("classifier down");
        };
        var g = new ModerationGuardrail(throwing);
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                g.inspectRequest(req("anything")));
    }

    @Test
    void failOpenAdmitsOnDetectorError() {
        ModerationDetector erroring = text -> ModerationDetector.ModerationResult.error("boom");
        var g = new ModerationGuardrail(erroring).failOpen();
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                g.inspectRequest(req("anything")),
                "fail-open mode must admit when the detector is unavailable");
    }

    // --- ModerationCategory parsing --------------------------------------

    @Test
    void categoryTokenParsingIsTolerant() {
        assertEquals(ModerationCategory.SELF_HARM,
                ModerationCategory.fromToken("Self Harm").orElseThrow());
        assertEquals(ModerationCategory.SELF_HARM,
                ModerationCategory.fromToken("self_harm").orElseThrow());
        assertEquals(ModerationCategory.ILLICIT,
                ModerationCategory.fromToken("ILLICIT").orElseThrow());
        assertTrue(ModerationCategory.fromToken("banana").isEmpty());
        assertTrue(ModerationCategory.fromToken(null).isEmpty());
    }

    // --- LlmModerationDetector -------------------------------------------

    @Test
    void llmDetectorParsesCategoryList() {
        var clean = LlmModerationDetector.parse("NONE");
        assertFalse(clean.isFlagged());
        assertFalse(clean.errored());

        var flagged = LlmModerationDetector.parse("violence, illicit");
        assertEquals(Set.of(ModerationCategory.VIOLENCE, ModerationCategory.ILLICIT),
                flagged.flagged());

        // Unmappable reply biases to clean (trust the cheaper tier), not error.
        var weird = LlmModerationDetector.parse("I cannot help with that");
        assertFalse(weird.isFlagged());
        assertFalse(weird.errored());
    }

    @Test
    void llmDetectorGuardrailBlocksFlaggedRuntime() {
        var runtime = new StubRuntime("violence");
        var g = new ModerationGuardrail(new LlmModerationDetector(runtime));
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                g.inspectRequest(req("ambiguous text the rules miss")));
    }

    @Test
    void llmDetectorGuardrailFailsClosedOnRuntimeError() {
        var runtime = new StubRuntime(null) {
            @Override
            public String generate(AgentExecutionContext context, Duration timeout) {
                throw new RuntimeException("model 503");
            }
        };
        var g = new ModerationGuardrail(new LlmModerationDetector(runtime));
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                g.inspectRequest(req("anything")),
                "a moderation runtime outage must fail closed");
    }

    /** Minimal AgentRuntime whose {@code generate} returns a canned classification reply. */
    private static class StubRuntime implements AgentRuntime {
        private final String reply;

        StubRuntime(String reply) {
            this.reply = reply;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send(reply == null ? "" : reply);
            session.complete();
        }

        @Override
        public String generate(AgentExecutionContext context, Duration timeout) {
            return reply;
        }

        @Override
        public void configure(org.atmosphere.ai.AiConfig.LlmSettings settings) {
            // no-op test stub
        }
    }
}
