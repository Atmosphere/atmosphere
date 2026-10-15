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
package org.atmosphere.coordinator.journal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A judge that never judged was journaled as a passing score — the startup-team
 * coordination journal read "EVAL research-agent [llm-judge] 1.0 — Empty judge
 * response PASS" after the judge's LLM call failed. A skipped evaluation now
 * renders as skipped, with no score.
 */
class SkippedEvaluationJournalTest {

    @Test
    void aSkippedEvaluationRendersAsSkippedWithoutAScore() {
        var event = new CoordinationEvent.AgentEvaluated("ceo", "research-agent", "llm-judge",
                0.0, true, "Empty judge response", Instant.now(), true);

        var table = JournalFormat.MARKDOWN.format(List.of(event));

        assertTrue(table.contains("[llm-judge] skipped"), table);
        assertTrue(table.contains("SKIPPED"), table);
        assertFalse(table.contains("PASS"), "a judge that did not run is not a pass: " + table);
        assertFalse(table.contains("0.0"), "a skipped evaluation has no score: " + table);
        assertTrue(event.toLogLine().contains("skipped"), event.toLogLine());
    }

    @Test
    void anEvaluationThatTookPlaceStillRendersItsScore() {
        var event = new CoordinationEvent.AgentEvaluated("ceo", "research-agent", "llm-judge",
                0.8, true, "solid", Instant.now());

        var table = JournalFormat.MARKDOWN.format(List.of(event));

        assertTrue(table.contains("[llm-judge] 0.8"), table);
        assertTrue(table.contains("PASS"), table);
    }
}
