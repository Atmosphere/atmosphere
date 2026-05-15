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
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Outcome of a single evaluation run — typically captured by CI after each
 * model / prompt change and posted to {@code /api/admin/evals/runs} so the
 * admin control plane surfaces a pass/fail trend over time.
 *
 * <p>Fields mirror {@code LlmJudge.JudgeRun} so a CI pipeline that already
 * captures golden eval baselines via {@code atmosphere-ai-test} can submit
 * the result without re-serializing. The {@code baseline} field names the
 * fixture this run was scored against (used for grouping in the UI);
 * {@code agentVersion} pins which build produced the agent response so a
 * regression can be traced to a specific commit.</p>
 */
public record EvalRun(
        String id,
        String baseline,
        Instant timestamp,
        String agentVersion,
        String prompt,
        String judgeResponse,
        Boolean verdict,
        Map<String, Double> scores,
        String judgeModel,
        boolean passed,
        String notes
) {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_\\-.:]+$");

    public EvalRun {
        id = requireSafeId(id, "id");
        baseline = requireSafeId(baseline, "baseline");
        Objects.requireNonNull(timestamp, "timestamp");
        agentVersion = agentVersion != null ? agentVersion : "";
        prompt = prompt != null ? prompt : "";
        judgeResponse = judgeResponse != null ? judgeResponse : "";
        scores = scores != null ? Map.copyOf(scores) : Map.of();
        judgeModel = judgeModel != null ? judgeModel : "";
        notes = notes != null ? notes : "";
    }

    private static String requireSafeId(String value, String label) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must match [A-Za-z0-9_\\-.:]+ (was: " + value + ")");
        }
        return value;
    }
}
