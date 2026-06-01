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
package org.atmosphere.interactions;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit coverage for the core Interaction value types and id boundary validation. */
class InteractionRecordsTest {

    private Interaction running() {
        var now = Instant.parse("2026-06-01T00:00:00Z");
        return new Interaction("int-1", null, "conv-1", "agent", "alice", "gpt",
                InteractionStatus.RUNNING, false, true, List.of(), null, null, null, now, now);
    }

    @Test
    void statusTerminalClassification() {
        assertTrue(InteractionStatus.COMPLETED.isTerminal());
        assertTrue(InteractionStatus.FAILED.isTerminal());
        assertTrue(InteractionStatus.CANCELLED.isTerminal());
        assertFalse(InteractionStatus.CREATED.isTerminal());
        assertFalse(InteractionStatus.RUNNING.isTerminal());
    }

    @Test
    void stepsAreDefensivelyCopied() {
        var step = new InteractionStep(0, "text", "hi", null, null, null, Instant.now());
        var mutable = new java.util.ArrayList<>(List.of(step));
        var interaction = running().withResult(InteractionStatus.COMPLETED, mutable,
                "hi", null, null, Instant.now());
        mutable.clear();
        assertEquals(1, interaction.steps().size(), "stored steps must not reflect later mutation");
        assertThrows(UnsupportedOperationException.class, () -> interaction.steps().add(step));
    }

    @Test
    void withAppendedStepProducesGrowingImmutableLog() {
        var base = running();
        var s0 = new InteractionStep(0, "text", "a", null, null, null, Instant.now());
        var s1 = new InteractionStep(1, "text", "b", null, null, null, Instant.now());
        var after = base.withAppendedStep(s0, Instant.now()).withAppendedStep(s1, Instant.now());
        assertEquals(0, base.steps().size(), "original interaction is unchanged");
        assertEquals(2, after.steps().size());
        assertEquals("b", after.steps().get(1).text());
    }

    @Test
    void requestFactoryDefaults() {
        var req = InteractionRequest.of("hello");
        assertTrue(req.store(), "of() defaults to persisted");
        assertFalse(req.background(), "of() defaults to foreground");
        assertEquals("hello", req.message());
        assertTrue(req.tools().isEmpty());
        assertEquals("p", req.withPrevious("p").previousInteractionId());
        assertTrue(req.withBackground(true).background());
        assertFalse(req.withStore(false).store());
    }

    @Test
    void queryClampsLimitAndMatches() {
        assertEquals(InteractionQuery.DEFAULT_LIMIT, new InteractionQuery(null, null, null, 0).limit());
        assertEquals(InteractionQuery.MAX_LIMIT, new InteractionQuery(null, null, null, 99_999).limit());
        assertEquals(5, new InteractionQuery(null, null, null, 5).limit());

        var i = running();
        assertTrue(InteractionQuery.forUser("alice").matches(i));
        assertFalse(InteractionQuery.forUser("bob").matches(i));
        assertTrue(InteractionQuery.forConversation("conv-1").matches(i));
        assertFalse(new InteractionQuery(null, null, InteractionStatus.COMPLETED, 10).matches(i));
    }

    @Test
    void idValidationRejectsTraversalAndAcceptsMinted() {
        assertTrue(InteractionIds.isValid(InteractionIds.mint()));
        assertTrue(InteractionIds.isValid("int-abc_123"));
        assertFalse(InteractionIds.isValid(null));
        assertFalse(InteractionIds.isValid(""));
        assertFalse(InteractionIds.isValid("../evil"));
        assertFalse(InteractionIds.isValid("a/b"));
        assertFalse(InteractionIds.isValid("a b"));
        assertFalse(InteractionIds.isValid("a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> InteractionIds.requireValid("../evil"));
        assertEquals("int-ok", InteractionIds.requireValid("int-ok"));
    }
}
