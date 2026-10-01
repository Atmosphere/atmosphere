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

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

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

    @Test
    void aFlaggedCategoryBlocksEvenWhenTheResultAlsoErroredInFailOpenMode() {
        // A detector that decided some categories and could not decide others.
        ModerationDetector partial = text -> new ModerationDetector.ModerationResult(
                Set.of(ModerationCategory.VIOLENCE), java.util.Map.of(), true, "hate undecided");
        var failOpen = new ModerationGuardrail(partial).failOpen();
        var blocked = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                failOpen.inspectRequest(req("anything")),
                "fail-open admits an undecided category, never a flagged one");
        assertTrue(blocked.reason().contains("VIOLENCE"), blocked.reason());
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                failOpen.blocking(ModerationCategory.HATE).inspectRequest(req("anything")),
                "a flagged category the guardrail does not block leaves only the error, which fail-open admits");
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                new ModerationGuardrail(partial).blocking(ModerationCategory.HATE).inspectRequest(req("anything")),
                "fail-closed blocks on the error");
    }

    @Test
    void anUndecidedCategoryTheGuardrailDoesNotBlockDoesNotGateTheTurn() {
        // HATE is blocked; SEXUAL is not. A flag on SEXUAL passes, so SEXUAL
        // left undecided must pass too, while HATE left undecided blocks.
        ModerationDetector sexualFlagged = text -> ModerationDetector.ModerationResult.flagged(
                Set.of(ModerationCategory.SEXUAL), java.util.Map.of(), "sexual");
        ModerationDetector sexualUndecided = text -> ModerationDetector.ModerationResult.undecided(
                Set.of(), java.util.Map.of(), Set.of(ModerationCategory.SEXUAL), "sexual undecided");
        ModerationDetector hateUndecided = text -> ModerationDetector.ModerationResult.undecided(
                Set.of(), java.util.Map.of(), Set.of(ModerationCategory.HATE), "hate undecided");
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                new ModerationGuardrail(sexualFlagged).blocking(ModerationCategory.HATE).inspectRequest(req("x")));
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                new ModerationGuardrail(sexualUndecided).blocking(ModerationCategory.HATE).inspectRequest(req("x")),
                "an undecided category the guardrail does not block is treated no stricter than a flag on it");
        var blocked = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                new ModerationGuardrail(hateUndecided).blocking(ModerationCategory.HATE).inspectRequest(req("x")),
                "an undecided blocked category fails closed");
        assertTrue(blocked.reason().contains("moderation unavailable"), blocked.reason());
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                new ModerationGuardrail(sexualUndecided).inspectRequest(req("x")),
                "with every category blocked, an undecided SEXUAL fails closed");
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                new ModerationGuardrail(hateUndecided).failOpen().inspectRequest(req("x")),
                "fail-open admits an undecided blocked category");
    }

    @Test
    void aWholeDetectorFailureBlocksWhateverTheGuardrailIsNarrowedTo() {
        ModerationDetector erroring = text -> ModerationDetector.ModerationResult.error("boom");
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                new ModerationGuardrail(erroring).blocking(ModerationCategory.HATE).inspectRequest(req("x")),
                "an error naming no undecided category is a failure of the whole detector");
    }

    @Test
    void undecidedImpliesErrored() {
        var result = new ModerationDetector.ModerationResult(Set.of(), java.util.Map.of(), false, "",
                Set.of(ModerationCategory.HATE));
        assertTrue(result.errored(), "a result with an undecided category is never clean");
    }

    @Test
    void theGuardrailAsksTheDetectorAboutItsBlockedCategoriesOnly() {
        var asked = new java.util.concurrent.atomic.AtomicReference<Set<ModerationCategory>>();
        var detector = new ModerationDetector() {
            @Override
            public ModerationResult detect(String text) {
                throw new AssertionError("the guardrail must pass its blocked categories");
            }

            @Override
            public ModerationResult detect(String text, Set<ModerationCategory> categories) {
                asked.set(Set.copyOf(categories));
                return ModerationResult.clean();
            }
        };
        new ModerationGuardrail(detector).blocking(ModerationCategory.HATE, ModerationCategory.VIOLENCE)
                .inspectRequest(req("x"));
        assertEquals(Set.of(ModerationCategory.HATE, ModerationCategory.VIOLENCE), asked.get());
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

    // LlmModerationDetector: see LlmModerationDetectorTest and
    // LlmModerationDetectorFailClosedTest.
}
