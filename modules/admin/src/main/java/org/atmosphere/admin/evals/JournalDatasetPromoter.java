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
package org.atmosphere.admin.evals;

import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns a recorded {@code CoordinationJournal} interaction into an
 * {@link EvalCase} — the "trace → dataset" half of the eval flywheel. The
 * operator picks a coordination worth keeping as a regression fixture; the
 * promoter extracts its prompt (the dispatched task) and reference answer (the
 * final agent output) into a dataset row.
 *
 * <p>Pure extraction over the journal's events, so it is reproducible and
 * unit-testable without a live journal backend.</p>
 */
public final class JournalDatasetPromoter {

    /** Argument keys that commonly carry the user-facing input, in priority order. */
    private static final List<String> PROMPT_KEYS =
            List.of("message", "prompt", "input", "query", "task", "question", "text");

    private final CoordinationJournal journal;

    public JournalDatasetPromoter(CoordinationJournal journal) {
        this.journal = journal != null ? journal : CoordinationJournal.NOOP;
    }

    /**
     * Promote the coordination with {@code coordinationId} from the journal into
     * an {@link EvalCase}.
     *
     * @return the case, or empty when the coordination has no events or never
     *         produced an agent result to use as the reference answer
     */
    public Optional<EvalCase> promote(String coordinationId, List<String> tags) {
        return promote(coordinationId, journal.retrieve(coordinationId), tags);
    }

    /** Pure form over an explicit event list — the unit-tested core. */
    static Optional<EvalCase> promote(String coordinationId, List<CoordinationEvent> events,
                                      List<String> tags) {
        if (coordinationId == null || coordinationId.isBlank()
                || events == null || events.isEmpty()) {
            return Optional.empty();
        }
        String prompt = null;
        String reference = null;
        var captured = Instant.EPOCH;
        for (var event : events) {
            if (event instanceof CoordinationEvent.AgentDispatched dispatched && prompt == null) {
                prompt = describePrompt(dispatched);
            }
            if (event instanceof CoordinationEvent.AgentCompleted completed
                    && completed.resultText() != null && !completed.resultText().isBlank()) {
                // Last successful completion wins — the coordination's final answer.
                reference = completed.resultText();
                captured = completed.timestamp() != null ? completed.timestamp() : captured;
            }
        }
        if (reference == null) {
            return Optional.empty();
        }
        var id = "journal-" + EvalCase.safeIdFragment(coordinationId);
        return Optional.of(new EvalCase(id, prompt != null ? prompt : "",
                reference, "journal:" + coordinationId, tags, captured));
    }

    private static String describePrompt(CoordinationEvent.AgentDispatched dispatched) {
        for (var key : PROMPT_KEYS) {
            var value = dispatched.args().get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        var base = dispatched.skill() != null ? dispatched.skill() : dispatched.agentName();
        return dispatched.args().isEmpty() ? base : base + " " + dispatched.args();
    }
}
