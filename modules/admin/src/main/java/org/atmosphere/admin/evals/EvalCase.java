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

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A single row in an evaluation <em>dataset</em> — an input case the agent can
 * be re-run against and scored. Distinct from {@link EvalRun}, which is the
 * <em>result</em> of scoring an execution: an {@code EvalCase} is the input
 * (prompt + optional reference answer) captured for repeated evaluation.
 *
 * <p>Cases are typically <strong>promoted</strong> from real production traffic
 * — a {@code CoordinationJournal} interaction the operator decides is worth
 * turning into a regression fixture (see {@link JournalDatasetPromoter}) — which
 * is the "trace → dataset" half of the eval flywheel.</p>
 *
 * @param id         stable identifier ({@code [A-Za-z0-9_\-.:]+})
 * @param prompt     the input the agent should be evaluated on
 * @param reference  the expected / reference answer, or {@code ""} when the case
 *                   is graded by a rubric rather than an exact reference
 * @param source     provenance, e.g. {@code "journal:<coordinationId>"} or
 *                   {@code "manual"}
 * @param tags       free-form labels for filtering / grouping
 * @param capturedAt when the case was added to the dataset
 */
public record EvalCase(
        String id,
        String prompt,
        String reference,
        String source,
        List<String> tags,
        Instant capturedAt) {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_\\-.:]+$");

    public EvalCase {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must match [A-Za-z0-9_\\-.:]+ (was: " + id + ")");
        }
        prompt = prompt != null ? prompt : "";
        reference = reference != null ? reference : "";
        source = source != null ? source : "manual";
        tags = tags != null ? List.copyOf(tags) : List.of();
        capturedAt = capturedAt != null ? capturedAt : Instant.EPOCH;
    }

    /** Sanitize an arbitrary string into a safe id fragment. */
    public static String safeIdFragment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "case";
        }
        var cleaned = raw.replaceAll("[^A-Za-z0-9_\\-.:]", "-");
        return cleaned.isBlank() ? "case" : cleaned;
    }
}
