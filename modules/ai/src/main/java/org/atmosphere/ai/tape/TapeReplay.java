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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic replay of a recorded {@link TapeRun}. Reads a run's tape back
 * and reconstructs the session — the prompt messages, the assistant output, and
 * any tool calls — <em>without invoking any model</em>. This is the "tape as
 * source of truth" property that makes the session tape a reproducibility and
 * test oracle: given only the tape, the exact recorded session is rebuilt.
 *
 * <p>{@link #reconstructTree(TapeStore, String)} rebuilds a whole multi-agent
 * coordination as a tree — the coordinator run plus every fan-out child run
 * linked to it by {@link TapeRun#parentRunId()} — so a team session (a CEO
 * coordinator delegating to specialist agents) replays as one ordered
 * transcript.
 *
 * <p>Reconstruction is the base layer; re-driving a reconstructed input against
 * a live {@code AgentRuntime} (e.g. a distilled student model) is layered on top
 * by callers that hold a runtime, and always starts from {@link #reconstruct}.
 *
 * <p>Payload decoding mirrors {@link TapeTrainingExtractor}: {@code input} steps
 * carry the prompt as a {@code messages} array, {@code text} steps are segments
 * of the assistant output (coalesced in {@code seq} order), and {@code
 * tool-start} steps carry {@code toolName}/{@code arguments}. Unparseable steps
 * are skipped (logged at trace), never fatal.
 */
public final class TapeReplay {

    private static final Logger logger = LoggerFactory.getLogger(TapeReplay.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TapeReplay() {
    }

    /**
     * Reconstruct a single run from its tape.
     *
     * @return the reconstructed run, or empty when {@code runId} is unknown to
     *         the store
     */
    public static Optional<ReplayedRun> reconstruct(TapeStore store, String runId) {
        return findRun(store, runId).map(run -> reconstruct(store, run));
    }

    /**
     * Reconstruct a run whose row is already in hand (avoids a second scan when
     * the caller already listed the runs).
     */
    public static ReplayedRun reconstruct(TapeStore store, TapeRun run) {
        List<TapeStep> steps = store.readSteps(run.runId(), 0, 0);
        List<ReplayedRun.Message> input = List.of();
        var output = new StringBuilder();
        List<ReplayedRun.ToolCall> tools = new ArrayList<>();
        for (TapeStep step : steps) {
            switch (step.kind()) {
                case "input" -> input = decodeMessages(step);
                case "text" -> appendText(output, step);
                case "tool-start" -> decodeTool(step).ifPresent(tools::add);
                default -> { /* metadata / progress / boundary steps carry no transcript text */ }
            }
        }
        return new ReplayedRun(run.runId(), run.tapeId(), run.parentRunId(), run.status(),
                run.model(), run.runtimeName(), run.endpoint(), run.startedAt(), run.endedAt(),
                run.stepCount(), input, output.toString(), List.copyOf(tools));
    }

    /**
     * Reconstruct a multi-agent coordination tree rooted at {@code rootRunId}:
     * the root run plus every run whose {@link TapeRun#parentRunId()} is
     * {@code rootRunId}, children ordered by start time. A single store scan
     * covers both root and children.
     *
     * @return the tree, or empty when {@code rootRunId} is unknown
     */
    public static Optional<ReplayedTree> reconstructTree(TapeStore store, String rootRunId) {
        // One scan (bounded by the store's own retention cap) yields the root
        // row and all candidate children — listRuns cannot filter by parentRunId.
        List<TapeRun> all = store.listRuns(new TapeQuery(null, null, store.maxRuns()));
        TapeRun rootRow = null;
        var childRows = new ArrayList<TapeRun>();
        for (TapeRun run : all) {
            if (rootRunId.equals(run.runId())) {
                rootRow = run;
            } else if (rootRunId.equals(run.parentRunId())) {
                childRows.add(run);
            }
        }
        if (rootRow == null) {
            return Optional.empty();
        }
        childRows.sort(Comparator.comparingLong(TapeRun::startedAt));
        var children = new ArrayList<ReplayedRun>(childRows.size());
        for (TapeRun child : childRows) {
            children.add(reconstruct(store, child));
        }
        return Optional.of(new ReplayedTree(reconstruct(store, rootRow), List.copyOf(children)));
    }

    private static Optional<TapeRun> findRun(TapeStore store, String runId) {
        for (TapeRun run : store.listRuns(new TapeQuery(null, null, store.maxRuns()))) {
            if (run.runId().equals(runId)) {
                return Optional.of(run);
            }
        }
        return Optional.empty();
    }

    private static List<ReplayedRun.Message> decodeMessages(TapeStep step) {
        try {
            JsonNode messages = MAPPER.readTree(step.payload()).get("messages");
            if (messages == null || !messages.isArray()) {
                return List.of();
            }
            var out = new ArrayList<ReplayedRun.Message>();
            for (JsonNode node : messages) {
                JsonNode role = node.get("role");
                JsonNode content = node.get("content");
                out.add(new ReplayedRun.Message(
                        role != null && role.isString() ? role.stringValue() : "user",
                        content != null && content.isString() ? content.stringValue() : ""));
            }
            return List.copyOf(out);
        } catch (RuntimeException e) {
            logger.trace("unparseable input step: {}", e.toString());
            return List.of();
        }
    }

    private static void appendText(StringBuilder output, TapeStep step) {
        try {
            JsonNode text = MAPPER.readTree(step.payload()).get("text");
            if (text != null && text.isString()) {
                output.append(text.stringValue());
            }
        } catch (RuntimeException e) {
            logger.trace("unparseable tape text step at seq {}: {}", step.seq(), e.toString());
        }
    }

    private static Optional<ReplayedRun.ToolCall> decodeTool(TapeStep step) {
        try {
            JsonNode node = MAPPER.readTree(step.payload());
            JsonNode name = node.get("toolName");
            JsonNode args = node.get("arguments");
            if (name == null || !name.isString()) {
                return Optional.empty();
            }
            return Optional.of(new ReplayedRun.ToolCall(
                    name.stringValue(), args == null || args.isNull() ? "" : args.toString()));
        } catch (RuntimeException e) {
            logger.trace("unparseable tool-start step at seq {}: {}", step.seq(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * One deterministically reconstructed run: the prompt messages, the coalesced
     * assistant output, and the tool calls, plus the run's identity/status.
     */
    public record ReplayedRun(String runId, String tapeId, String parentRunId, TapeStatus status,
                              String model, String runtimeName, String endpoint,
                              long startedAt, Long endedAt, long stepCount,
                              List<Message> input, String output, List<ToolCall> tools) {

        /** A reconstructed prompt message (system / user / assistant / history turn). */
        public record Message(String role, String content) {
        }

        /** A reconstructed tool invocation: the tool name and its arguments as JSON. */
        public record ToolCall(String name, String arguments) {
        }
    }

    /**
     * A reconstructed multi-agent coordination: the root (coordinator) run and
     * its fan-out child runs, ordered by start time.
     */
    public record ReplayedTree(ReplayedRun root, List<ReplayedRun> children) {

        /** Total runs in the tree (coordinator + specialists). */
        public int runCount() {
            return 1 + children.size();
        }
    }
}
