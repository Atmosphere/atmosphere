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
package org.atmosphere.ai.tape;

import org.atmosphere.ai.llm.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

/**
 * Turns recorded session tapes into supervised fine-tuning data — the
 * TapeAgents "make_training_text" idea, realized at Atmosphere's tape boundary.
 *
 * <p>Each {@code COMPLETED} tape run becomes one training example: the input is
 * the run's {@code input} step (the prompt messages — system + history + user —
 * the {@link TapeRecordingSession} records at dispatch), and the target is the
 * assistant completion reconstructed from the run's {@code text} steps in seq
 * order. Because the tape records both sides, the extractor is self-contained:
 * it reads one store and needs no cross-store join. The output is chat-format
 * JSONL ({@code {"messages":[…]}}), the format MLX-LM / HuggingFace chat
 * fine-tuners consume.</p>
 *
 * <p><b>Fidelity, not silent truncation.</b> A run is emitted only when all
 * hold: terminal status is {@code COMPLETED}, the run has an {@code input} step
 * with at least one message, and it produced non-empty completion text. Every
 * dropped run is counted by reason and, up to a bound, noted — never dropped
 * silently (matches the tape's own no-silent-caps discipline). Runs still
 * {@code OPEN} (in flight), {@code ERROR}, {@code CANCELLED} or
 * {@code ABANDONED} are excluded: a partial or failed turn is not a training
 * signal.</p>
 *
 * <p>Stateless and side-effect free; the caller owns the {@link TapeStore}.</p>
 *
 * <p><b>RAG note.</b> The {@code input} step is the prompt <em>as produced at the
 * session boundary</em>. On the endpoint path a RAG provider rewrites the user
 * message with retrieved context before dispatch, so the input records
 * (question + context); on the pipeline path retrieval runs inside the runtime
 * (below the boundary), so the input records the bare question. For RAG turns
 * the two paths therefore yield different training inputs — each faithful to
 * what its path dispatched. Prefer endpoint-path tapes when the retrieved
 * context must be part of the training pair.</p>
 */
public final class TapeTrainingExtractor {

    private static final Logger logger = LoggerFactory.getLogger(TapeTrainingExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Cap on retained skip notes so the report itself stays bounded (Invariant #3). */
    private static final int MAX_NOTES = 50;

    /** One supervised example: the prompt messages plus the assistant completion. */
    public record TrainingExample(List<ChatMessage> messages) {
        public TrainingExample {
            messages = List.copyOf(messages);
        }
    }

    /** What was emitted and what was dropped, by reason — no silent caps. */
    public record Report(int runsSeen, int emitted, int skippedNotTerminal,
                         int skippedNoInput, int skippedNoOutput, List<String> notes) {
        public Report {
            notes = List.copyOf(notes);
        }
    }

    public record Result(List<TrainingExample> examples, Report report) {
        public Result {
            examples = List.copyOf(examples);
        }
    }

    /**
     * Fold the newest {@code maxRuns} tape runs (0 = no cap) into training
     * examples.
     */
    public Result extract(TapeStore tape, int maxRuns) {
        var examples = new ArrayList<TrainingExample>();
        var notes = new ArrayList<String>();
        int seen = 0, notTerminal = 0, noInput = 0, noOutput = 0;

        for (var run : tape.listRuns(new TapeQuery(null, null, maxRuns))) {
            seen++;
            if (run.status() != TapeStatus.COMPLETED) {
                notTerminal++;
                note(notes, "skip " + shortId(run.runId()) + ": status=" + run.status());
                continue;
            }
            var steps = tape.readSteps(run.runId(), 0, 0);
            var prompt = inputMessages(steps);
            if (prompt.isEmpty()) {
                noInput++;
                note(notes, "skip " + shortId(run.runId()) + ": no input step");
                continue;
            }
            var completion = completionText(steps);
            if (completion.isBlank()) {
                noOutput++;
                note(notes, "skip " + shortId(run.runId()) + ": no completion text");
                continue;
            }
            var msgs = new ArrayList<>(prompt);
            msgs.add(ChatMessage.assistant(completion));
            examples.add(new TrainingExample(msgs));
        }
        var report = new Report(seen, examples.size(), notTerminal, noInput, noOutput, notes);
        logger.debug("Tape extraction: {} runs → {} examples (skipped notTerminal={}, noInput={}, "
                + "noOutput={})", seen, examples.size(), notTerminal, noInput, noOutput);
        return new Result(examples, report);
    }

    /** The prompt messages from the run's {@code input} step, or empty. */
    private List<ChatMessage> inputMessages(List<TapeStep> steps) {
        for (var step : steps) {
            if (!"input".equals(step.kind())) {
                continue;
            }
            try {
                var arr = MAPPER.readTree(step.payload()).get("messages");
                if (arr == null || !arr.isArray()) {
                    return List.of();
                }
                var out = new ArrayList<ChatMessage>();
                for (var node : arr) {
                    var role = node.get("role");
                    var content = node.get("content");
                    out.add(new ChatMessage(
                            role != null && role.isString() ? role.stringValue() : "user",
                            content != null && content.isString() ? content.stringValue() : ""));
                }
                return out;
            } catch (RuntimeException e) {
                logger.trace("unparseable input step: {}", e.toString());
                return List.of();
            }
        }
        return List.of();
    }

    /** Concatenate the run's {@code text} steps in seq order into the completion. */
    private String completionText(List<TapeStep> steps) {
        var sb = new StringBuilder();
        for (var step : steps) {
            if (!"text".equals(step.kind())) {
                continue;
            }
            try {
                var node = MAPPER.readTree(step.payload()).get("text");
                if (node != null && node.isString()) {
                    sb.append(node.stringValue());
                }
            } catch (RuntimeException e) {
                // A single malformed step never fails the whole extraction; the
                // completeness check drops the run if nothing survives.
                logger.trace("unparseable tape text step at seq {}: {}", step.seq(), e.toString());
            }
        }
        return sb.toString().strip();
    }

    private static void note(List<String> notes, String msg) {
        if (notes.size() < MAX_NOTES) {
            notes.add(msg);
        }
    }

    private static String shortId(String id) {
        return id != null && id.length() > 8 ? id.substring(0, 8) : id;
    }

    /**
     * Write examples as chat-format JSONL — one {@code {"messages":[…]}} object
     * per line, the format MLX-LM / HF chat fine-tuners read. Only {@code role}
     * and {@code content} are emitted (the two fields a text SFT run needs).
     */
    public static void writeJsonl(List<TrainingExample> examples, Appendable out) {
        try {
            for (var ex : examples) {
                var msgArray = MAPPER.createArrayNode();
                for (var m : ex.messages()) {
                    var o = MAPPER.createObjectNode();
                    o.put("role", m.role() == null ? "user" : m.role());
                    o.put("content", m.content() == null ? "" : m.content());
                    msgArray.add(o);
                }
                var root = MAPPER.createObjectNode();
                root.set("messages", msgArray);
                out.append(MAPPER.writeValueAsString(root)).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write training JSONL", e);
        }
    }

    /** Convenience: JSONL as a String. */
    public static String toJsonl(List<TrainingExample> examples) {
        var sb = new StringBuilder();
        writeJsonl(examples, sb);
        return sb.toString();
    }
}
