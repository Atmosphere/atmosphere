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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.TokenLogprob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-stream accumulator for chat-completions {@code choices[0].logprobs.content}
 * entries, spanning every tool-loop round of one top-level request. Folds the
 * capture into the {@link AiConfidence} the client emits on completion:
 * {@link AiConfidence.Source#DECISION_LOGPROBS} when a {@link DecisionField}
 * was designated, otherwise the historical
 * {@link AiConfidence.Source#LOGPROBS_NATIVE} mean.
 *
 * <p>Bounded (Invariant #3): at most {@value #MAX_LOGPROB_TOKENS} entries, and
 * top alternatives are kept only when a decision field is designated, at most
 * {@value DecisionField#MAX_TOP_LOGPROBS} per entry, and only for the current
 * round. Boundary-defensive
 * (Invariant #4): an entry missing a {@code token} string or a numeric
 * {@code logprob} is skipped, NaN is skipped, and a (theoretically impossible
 * but observed-in-the-wild) positive logprob is clamped to {@code 0.0} so a
 * malformed entry can never abort the stream via {@link TokenLogprob}'s
 * validation. Used from the single thread that reads the SSE stream.</p>
 */
final class LogprobCapture {

    private static final Logger logger = LoggerFactory.getLogger(LogprobCapture.class);

    /** Bound on captured logprob entries — matches the output ceiling a
     *  single response can realistically carry; beyond it the capture stops
     *  and the aggregate reflects the captured prefix (Invariant #3). */
    static final int MAX_LOGPROB_TOKENS = 4096;

    /**
     * One captured token.
     *
     * @param sampled the token the model emitted and its logprob
     * @param top     the provider's top alternatives at this position; empty
     *                when none were requested or returned
     */
    record Entry(TokenLogprob sampled, List<TokenLogprob> top) {
        Entry {
            top = List.copyOf(top);
        }
    }

    private final DecisionField decisionField;
    private final List<Entry> entries = new ArrayList<>();
    private int roundStart;
    private boolean truncationLogged;

    /**
     * @param decisionField the decision to score, or {@code null} for the
     *                      whole-response mean
     */
    LogprobCapture(DecisionField decisionField) {
        this.decisionField = decisionField;
    }

    /**
     * Mark the start of a live model round. The decision is scored on the
     * last round only — the final answer — so a tool-call round whose text
     * happens to mention the field cannot be mistaken for the decision. With
     * a decision field the earlier rounds are therefore never read (the
     * whole-response mean is not emitted in that mode), so they are dropped
     * here instead of holding their alternatives until completion.
     */
    void beginRound() {
        if (decisionField != null) {
            entries.clear();
        }
        roundStart = entries.size();
    }

    /** Fold one chunk's {@code choices[0].logprobs} node into the capture. */
    void capture(JsonNode logprobsNode) {
        if (logprobsNode == null || logprobsNode.isNull()) {
            return;
        }
        var content = logprobsNode.get("content");
        if (content == null || !content.isArray()) {
            return;
        }
        for (var entry : content) {
            if (entries.size() >= MAX_LOGPROB_TOKENS) {
                if (!truncationLogged) {
                    truncationLogged = true;
                    logger.debug("Logprob capture truncated at {} tokens", MAX_LOGPROB_TOKENS);
                }
                return;
            }
            var sampled = parse(entry);
            if (sampled == null) {
                continue;
            }
            entries.add(new Entry(sampled, decisionField != null
                    ? parseTop(entry.get("top_logprobs")) : List.of()));
        }
    }

    /**
     * The confidence to emit on completion, or {@code null} to stay silent so
     * the pipeline's model-reported-field fallback applies: nothing was
     * captured, or a decision field was designated but its distribution could
     * not be built (see {@link DecisionScorer#score}).
     */
    AiConfidence toConfidence() {
        if (entries.isEmpty()) {
            return null;
        }
        if (decisionField == null) {
            return AiConfidence.fromLogprobs(entries.stream().map(Entry::sampled).toList());
        }
        return DecisionScorer.score(entries.subList(roundStart, entries.size()), decisionField)
                .orElse(null);
    }

    private static List<TokenLogprob> parseTop(JsonNode topNode) {
        if (topNode == null || !topNode.isArray()) {
            return List.of();
        }
        var top = new ArrayList<TokenLogprob>(Math.min(topNode.size(), DecisionField.MAX_TOP_LOGPROBS));
        for (var alt : topNode) {
            if (top.size() >= DecisionField.MAX_TOP_LOGPROBS) {
                break;
            }
            var parsed = parse(alt);
            if (parsed != null) {
                top.add(parsed);
            }
        }
        return top;
    }

    private static TokenLogprob parse(JsonNode entry) {
        if (entry == null || !entry.isObject()) {
            return null;
        }
        var tokenNode = entry.get("token");
        var logprobNode = entry.get("logprob");
        if (tokenNode == null || !tokenNode.isString()
                || logprobNode == null || !logprobNode.isNumber()) {
            return null;
        }
        var logprob = logprobNode.asDouble();
        if (Double.isNaN(logprob)) {
            return null;
        }
        return new TokenLogprob(tokenNode.stringValue(), Math.min(logprob, 0.0));
    }
}
