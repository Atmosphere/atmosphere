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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TapeTrainingExtractor}: a COMPLETED run with an input step and text
 * steps folds to one (prompt → completion) example; every incomplete or partial
 * run is dropped and counted by reason (no silent truncation).
 */
class TapeTrainingExtractorTest {

    private final TapeTrainingExtractor extractor = new TapeTrainingExtractor();

    @Test
    void completedRunFoldsToOnePromptCompletionExample() {
        var store = new InMemoryTapeStore();
        store.begin(open("r1"));
        store.append("r1", List.of(
                inputStep("r1", 0, "You are a classifier.", "Classify: great!"),
                textStep("r1", 1, "POSI"),
                textStep("r1", 2, "TIVE"),
                step("r1", 3, "complete", "{\"v\":1}")));
        store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(4, 0, false));

        var result = extractor.extract(store, 0);

        assertEquals(1, result.examples().size(), "one COMPLETED run → one example");
        var msgs = result.examples().get(0).messages();
        assertEquals(3, msgs.size(), "system + user + assistant");
        assertEquals("system", msgs.get(0).role());
        assertEquals("user", msgs.get(1).role());
        assertEquals("Classify: great!", msgs.get(1).content());
        assertEquals("assistant", msgs.get(2).role());
        assertEquals("POSITIVE", msgs.get(2).content(),
                "text steps concatenate in seq order into the completion");
        assertEquals(0, result.report().skippedNoInput());
        assertEquals(0, result.report().skippedNoOutput());
    }

    @Test
    void runWithoutInputStepIsSkippedAndCounted() {
        var store = new InMemoryTapeStore();
        store.begin(open("r1"));
        store.append("r1", List.of(textStep("r1", 0, "orphan output")));
        store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(1, 0, false));

        var result = extractor.extract(store, 0);

        assertTrue(result.examples().isEmpty(), "no input step → no training pair");
        assertEquals(1, result.report().skippedNoInput());
        assertTrue(result.report().notes().stream().anyMatch(n -> n.contains("no input")));
    }

    @Test
    void nonTerminalRunIsExcluded() {
        var store = new InMemoryTapeStore();
        store.begin(open("r1"));
        store.append("r1", List.of(inputStep("r1", 0, "sys", "hi"), textStep("r1", 1, "hello")));
        // left OPEN — an in-flight turn is not a training signal

        var result = extractor.extract(store, 0);

        assertTrue(result.examples().isEmpty(), "OPEN run must not be extracted");
        assertEquals(1, result.report().skippedNotTerminal());
    }

    @Test
    void completedRunWithInputButNoOutputIsSkipped() {
        var store = new InMemoryTapeStore();
        store.begin(open("r1"));
        store.append("r1", List.of(inputStep("r1", 0, "sys", "hi")));
        store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(1, 0, false));

        var result = extractor.extract(store, 0);

        assertTrue(result.examples().isEmpty());
        assertEquals(1, result.report().skippedNoOutput());
    }

    @Test
    void writesChatFormatJsonl() {
        var store = new InMemoryTapeStore();
        store.begin(open("r1"));
        store.append("r1", List.of(inputStep("r1", 0, "sys", "hi"), textStep("r1", 1, "hey")));
        store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(2, 0, false));

        var jsonl = TapeTrainingExtractor.toJsonl(extractor.extract(store, 0).examples());

        var lines = jsonl.strip().split("\n");
        assertEquals(1, lines.length, "one example → one JSONL line");
        assertTrue(lines[0].startsWith("{\"messages\":["), "chat-format envelope: " + lines[0]);
        assertTrue(lines[0].contains("\"role\":\"assistant\"") && lines[0].contains("\"content\":\"hey\""),
                "assistant completion present: " + lines[0]);
    }

    // ---- fixtures ----

    private static TapeRun open(String runId) {
        return new TapeRun(runId, "tape-a", "sess", null, null, "/chat", "m", "built-in",
                1000L, TapeStatus.OPEN, null, 0, 0, false, null);
    }

    private static TapeStep step(String runId, long seq, String kind, String payload) {
        return new TapeStep(runId, seq, kind, payload, 1000L + seq);
    }

    private static TapeStep textStep(String runId, long seq, String text) {
        return step(runId, seq, "text", "{\"v\":1,\"text\":\"" + text + "\"}");
    }

    private static TapeStep inputStep(String runId, long seq, String system, String user) {
        var payload = "{\"v\":1,\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + system + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + user + "\"}]}";
        return step(runId, seq, "input", payload);
    }
}
