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

import org.atmosphere.ai.AiEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the Tier-1 plaintext-capture P1 (tape half): tool
 * arguments, tool results, and the input prompt used to persist verbatim with
 * no redaction hook. With a {@link PiiTapeRedactor} installed via
 * {@link TapeRecorder.Config}, PII is masked at capture time on BOTH write
 * paths — the streaming session and the one-shot A2A record. The default
 * (no redactor) stays byte-verbatim.
 */
class TapeRedactionTest {

    private static final String EMAIL = "alice@example.com";
    private static final String SSN = "123-45-6789";

    private static TapeRecorder.Config redactingConfig() {
        return new TapeRecorder.Config(8192, 262_144, Duration.ofMinutes(30),
                Duration.ofSeconds(10), new PiiTapeRedactor());
    }

    /** No-op leaf session (mirrors TapeRecorderTest's private NoopDelegate). */
    private static final class NoopLeaf implements org.atmosphere.ai.StreamingSession {
        @Override public String sessionId() { return "leaf"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public void emit(AiEvent event) { }
        @Override public boolean isClosed() { return false; }
        @Override public boolean hasErrored() { return false; }
    }

    private static TapeRecordingSession pipelineSession(TapeRecorder recorder) {
        return new TapeRecordingSession(recorder, new NoopLeaf(),
                TapeRunInfo.pipeline("client-1", "model-x", "rt"));
    }

    private static String allPayloads(InMemoryTapeStore store, String runId) {
        var sb = new StringBuilder();
        for (var step : store.readSteps(runId, 0, 0)) {
            sb.append(step.payload()).append('\n');
        }
        return sb.toString();
    }

    private static void awaitTerminal(InMemoryTapeStore store, String runId) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var terminal = store.listRuns(TapeQuery.all(0)).stream()
                    .anyMatch(r -> r.runId().equals(runId) && r.status() != TapeStatus.OPEN);
            if (terminal) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run " + runId + " never reached a terminal status");
    }

    @Test
    void installedRedactorMasksToolArgsResultsAndInputAtCapture() throws Exception {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store, redactingConfig());
        try {
            var session = pipelineSession(recorder);
            session.recordInput("You are helpful", List.of(),
                    "My SSN is " + SSN + ", email " + EMAIL);
            session.emit(new AiEvent.ToolStart("send_email", Map.of("to", EMAIL)));
            session.emit(new AiEvent.ToolResult("send_email", "sent to " + EMAIL));
            session.complete();
            awaitTerminal(store, session.tapeRunId());

            var payloads = allPayloads(store, session.tapeRunId());
            assertFalse(payloads.contains(EMAIL),
                    "tool args/results and input must not persist raw PII:\n" + payloads);
            assertFalse(payloads.contains(SSN),
                    "the input prompt must not persist a raw SSN:\n" + payloads);
            assertTrue(payloads.contains(PiiTapeRedactor.REPLACEMENT),
                    "masked values must carry the replacement marker:\n" + payloads);
        } finally {
            recorder.close();
        }
    }

    @Test
    void oneShotA2aRecordIsRedactedIdentically() {
        // Mode parity (Inv #7): the non-streaming recordCompletedRun path must
        // not leak what the streaming path masks.
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store, redactingConfig());
        try {
            recorder.recordCompletedRun("task-1", "/skill", null, "user-1",
                    "reach me at " + EMAIL, "ok, noted " + SSN, TapeStatus.COMPLETED);
            var runs = store.listRuns(TapeQuery.all(0));
            assertTrue(runs.size() == 1, "one-shot run must be recorded");
            var payloads = allPayloads(store, runs.get(0).runId());
            assertFalse(payloads.contains(EMAIL), payloads);
            assertFalse(payloads.contains(SSN), payloads);
            assertTrue(payloads.contains(PiiTapeRedactor.REPLACEMENT), payloads);
        } finally {
            recorder.close();
        }
    }

    @Test
    void defaultConfigStaysVerbatim() throws Exception {
        // No redactor installed -> the tape is a faithful record, exactly as
        // before this hook existed (opt-in, no default behavior change).
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store);
        try {
            var session = pipelineSession(recorder);
            session.emit(new AiEvent.ToolStart("send_email", Map.of("to", EMAIL)));
            session.complete();
            awaitTerminal(store, session.tapeRunId());
            assertTrue(allPayloads(store, session.tapeRunId()).contains(EMAIL),
                    "the default tape must record verbatim");
        } finally {
            recorder.close();
        }
    }
}
