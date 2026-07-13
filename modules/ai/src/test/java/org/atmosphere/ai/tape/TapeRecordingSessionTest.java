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

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link TapeRecordingSession} step semantics: capture-completeness pin,
 * text coalescing (semantic-boundary flush, TextComplete subsumption,
 * Embabel-style full-result dedup), terminals including the emit forms,
 * the metadata skip-list, typed usage/confidence normalization parity with
 * the endpoint metadata form, content descriptors, unserializable payload
 * containment, and late-bind re-keying.
 */
class TapeRecordingSessionTest {

    private InMemoryTapeStore store;
    private TapeRecorder recorder;

    @BeforeEach
    void setUp() {
        store = new InMemoryTapeStore();
        recorder = new TapeRecorder(store);
    }

    @AfterEach
    void tearDown() {
        recorder.close();
    }

    @Test
    void everyEventBearingStreamingSessionMethodIsOverridden() {
        // Complements DelegatingStreamingSessionContractTest: the tape must
        // OVERRIDE (not merely inherit a forward for) every event-bearing
        // method, or a new event seam would silently bypass the recording.
        var nonEventBearing = Set.of("sessionId", "runId", "injectables", "isClosed",
                "hasErrored", "stream", "handoff", "onTerminate");
        var missing = new ArrayList<String>();
        for (var m : StreamingSession.class.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge() || Modifier.isPrivate(m.getModifiers())
                    || nonEventBearing.contains(m.getName())) {
                continue;
            }
            try {
                TapeRecordingSession.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(m.getName());
            }
        }
        assertEquals(List.of(), missing,
                "TapeRecordingSession must override every event-bearing "
                        + "StreamingSession method — missing: " + missing);
    }

    @Test
    void textFlushedAsOneSegmentBeforeSemanticBoundary() throws Exception {
        var session = newPipelineSession(new RecordingDelegate());
        session.send("Hel");
        session.send("lo");
        session.emit(new AiEvent.ToolStart("weather", Map.of("city", "Montreal")));
        session.complete();

        var steps = awaitTerminalSteps(session.tapeRunId(), TapeStatus.COMPLETED);
        assertEquals(List.of("text", "tool-start", "complete"), kinds(steps));
        assertTrue(steps.get(0).payload().contains("\"text\":\"Hello\""),
                "coalesced segment must precede the boundary event: " + steps.get(0).payload());
        assertTrue(steps.get(1).payload().contains("\"toolName\":\"weather\""),
                "tool-start payload: " + steps.get(1).payload());
    }

    @Test
    void textCompleteSubsumesTheAccumulator() throws Exception {
        var session = newPipelineSession(new RecordingDelegate());
        session.send("partial ");
        session.emit(new AiEvent.TextComplete("the full answer"));
        session.complete();

        var steps = awaitTerminalSteps(session.tapeRunId(), TapeStatus.COMPLETED);
        assertEquals(List.of("text", "complete"), kinds(steps),
                "TextComplete must clear the accumulator and record exactly one TEXT step");
        assertTrue(steps.get(0).payload().contains("\"text\":\"the full answer\""),
                "the TEXT step must carry the authoritative fullText: " + steps.get(0).payload());
    }

    @Test
    void sendEqualToEntireAccumulatorIsSubsumedNotAppended() throws Exception {
        var session = newPipelineSession(new RecordingDelegate());
        session.emit(new AiEvent.TextDelta("Hello world"));
        // Embabel deployed path: emit(TextDelta) stream, then send(fullResult).
        session.send("Hello world");
        session.complete();

        var steps = awaitTerminalSteps(session.tapeRunId(), TapeStatus.COMPLETED);
        assertEquals(List.of("text", "complete"), kinds(steps),
                "the duplicate full-result send must be subsumed, not doubled");
        assertTrue(steps.get(0).payload().contains("\"text\":\"Hello world\""),
                steps.get(0).payload());
        assertFalse(steps.get(0).payload().contains("Hello worldHello world"),
                "dedup must not concatenate: " + steps.get(0).payload());
    }

    @Test
    void cumulativeTextCompleteAfterABoundaryFlushIsNotDoubleCounted() throws Exception {
        var session = newPipelineSession(new RecordingDelegate());
        session.send("AB");
        // Tool boundary flushes the pending "AB" as its own text step.
        session.emit(new AiEvent.ToolStart("search", Map.of("q", "x")));
        // TextComplete carries the CUMULATIVE response ("ABCD"). Only the
        // un-flushed suffix ("CD") must be recorded, so the two text steps
        // reconstruct the answer once — not "ABABCD".
        session.emit(new AiEvent.TextComplete("ABCD"));
        session.complete();

        var steps = awaitTerminalSteps(session.tapeRunId(), TapeStatus.COMPLETED);
        assertEquals(List.of("text", "tool-start", "text", "complete"), kinds(steps),
                "boundary flush then terminal full text: " + steps);
        assertTrue(steps.get(0).payload().contains("\"text\":\"AB\""), steps.get(0).payload());
        assertTrue(steps.get(2).payload().contains("\"text\":\"CD\""),
                "the cumulative TextComplete must record only the un-flushed suffix: "
                        + steps.get(2).payload());
        assertFalse(steps.get(2).payload().contains("ABCD"),
                "the already-flushed prefix must not be re-recorded: " + steps.get(2).payload());
    }

    @Test
    void everyTerminalFormReachesItsWriteOnceStatus() throws Exception {
        var complete = newPipelineSession(new RecordingDelegate());
        complete.complete();
        awaitStatus(complete.tapeRunId(), TapeStatus.COMPLETED);

        var completeSummary = newPipelineSession(new RecordingDelegate());
        completeSummary.complete("all done");
        var summarySteps = awaitTerminalSteps(completeSummary.tapeRunId(), TapeStatus.COMPLETED);
        assertTrue(summarySteps.get(summarySteps.size() - 1).payload()
                .contains("\"summary\":\"all done\""));

        var error = newPipelineSession(new RecordingDelegate());
        error.error(new IllegalStateException("boom"));
        var errorSteps = awaitTerminalSteps(error.tapeRunId(), TapeStatus.ERROR);
        assertTrue(errorSteps.get(errorSteps.size() - 1).payload().contains("\"message\":\"boom\""));

        var emitComplete = newPipelineSession(new RecordingDelegate());
        emitComplete.emit(new AiEvent.Complete("emitted", null));
        var emitSteps = awaitTerminalSteps(emitComplete.tapeRunId(), TapeStatus.COMPLETED);
        assertTrue(emitSteps.get(emitSteps.size() - 1).payload()
                .contains("\"summary\":\"emitted\""));

        var emitError = newPipelineSession(new RecordingDelegate());
        emitError.emit(new AiEvent.Error("rate limited", "rate_limit", true));
        var emitErrorSteps = awaitTerminalSteps(emitError.tapeRunId(), TapeStatus.ERROR);
        assertTrue(emitErrorSteps.get(emitErrorSteps.size() - 1).payload()
                .contains("\"code\":\"rate_limit\""));
    }

    @Test
    void metadataSkipListSkipsToolCallDeltaKeysButForwardsThem() throws Exception {
        var delegate = new RecordingDelegate();
        var session = newPipelineSession(delegate);
        session.sendMetadata("ai.toolCall.delta.call_1", "{\"city\":\"Mo");
        session.sendMetadata("ai.tokens.input", 5L);
        session.complete();

        var steps = awaitTerminalSteps(session.tapeRunId(), TapeStatus.COMPLETED);
        assertEquals(List.of("metadata", "complete"), kinds(steps),
                "only the non-skip-listed key may be taped");
        assertTrue(steps.get(0).payload().contains("\"key\":\"ai.tokens.input\""),
                steps.get(0).payload());
        assertTrue(delegate.calls.contains("metadata:ai.toolCall.delta.call_1"),
                "the skip-list affects the tape only — the wire delivery must be untouched");
    }

    @Test
    void typedUsageAndConfidenceNormalizeToTheEndpointMetadataForm() throws Exception {
        // Path A — pipeline form: the tape receives the TYPED calls.
        var typedDelegate = new RecordingDelegate();
        var typed = newPipelineSession(typedDelegate);
        var usage = TokenUsage.of(10, 20, 30);
        var confidence = AiConfidence.reported(0.9);
        typed.usage(usage);
        typed.confidence(confidence);
        typed.complete();
        var typedSteps = awaitTerminalSteps(typed.tapeRunId(), TapeStatus.COMPLETED);

        // Path B — endpoint form: AiStreamingSession lacks usage()/confidence()
        // overrides, so the interface DEFAULTS fire one hop above the tape and
        // the tape sees only the resulting sendMetadata calls. Simulate with a
        // wrapper that does not override the defaults.
        var endpointTape = newPipelineSession(new RecordingDelegate());
        var endpointChain = new NoOverrideForwarder(endpointTape);
        endpointChain.usage(usage);
        endpointChain.confidence(confidence);
        endpointTape.complete();
        var endpointSteps = awaitTerminalSteps(endpointTape.tapeRunId(), TapeStatus.COMPLETED);

        assertEquals(payloadsOf(endpointSteps), payloadsOf(typedSteps),
                "MODE PARITY: identical logical events must produce an identical tape "
                        + "through both the typed and the endpoint-metadata form");
        assertTrue(payloadsOf(typedSteps).stream()
                        .anyMatch(p -> p.contains("\"key\":\"ai.tokens.input\",\"value\":10")),
                "normalized ai.tokens.* metadata steps expected: " + payloadsOf(typedSteps));
        assertTrue(payloadsOf(typedSteps).stream()
                        .anyMatch(p -> p.contains("\"key\":\"ai.confidence.source\"")),
                "normalized ai.confidence.* metadata steps expected: " + payloadsOf(typedSteps));
        assertSame(usage, typedDelegate.usageReceived,
                "the typed record must forward to the delegate unchanged");
    }

    @Test
    void toolCallDeltaIsSkippedButForwardedTyped() throws Exception {
        var delegate = new RecordingDelegate();
        var session = newPipelineSession(delegate);
        session.toolCallDelta("call_9", "{\"arg\":");
        session.complete();

        var steps = awaitTerminalSteps(session.tapeRunId(), TapeStatus.COMPLETED);
        assertEquals(List.of("complete"), kinds(steps),
                "toolCallDelta must record nothing on the tape");
        assertTrue(delegate.calls.contains("toolCallDelta:call_9"),
                "the typed call must still reach the delegate");
    }

    @Test
    void contentStepIsADescriptorNeverRawBytes() throws Exception {
        var bytes = "PNGDATA".getBytes(StandardCharsets.UTF_8);
        var session = newPipelineSession(new RecordingDelegate());
        session.sendContent(Content.image(bytes, "image/png"));
        session.complete();

        var steps = awaitTerminalSteps(session.tapeRunId(), TapeStatus.COMPLETED);
        assertEquals(List.of("content", "complete"), kinds(steps));
        var payload = steps.get(0).payload();
        var expectedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        assertTrue(payload.contains("\"contentType\":\"image\""), payload);
        assertTrue(payload.contains("\"mimeType\":\"image/png\""), payload);
        assertTrue(payload.contains("\"byteLength\":" + bytes.length), payload);
        assertTrue(payload.contains("\"sha256\":\"" + expectedSha + "\""), payload);
        assertFalse(payload.contains("PNGDATA"), "raw content must never be taped: " + payload);
        assertFalse(payload.contains(java.util.Base64.getEncoder().encodeToString(bytes)),
                "base64 content must never be taped: " + payload);
    }

    @Test
    void unserializablePayloadBecomesCountedPlaceholder() throws Exception {
        var session = newPipelineSession(new RecordingDelegate());
        session.emit(new AiEvent.ToolResult("lookup", new ThrowingBean()));
        session.complete();

        var steps = awaitTerminalSteps(session.tapeRunId(), TapeStatus.COMPLETED);
        assertEquals(List.of("tool-result", "complete"), kinds(steps));
        assertTrue(steps.get(0).payload().contains("\"_unserializable\""),
                "placeholder expected: " + steps.get(0).payload());
        assertEquals(1, recorder.unserializablePayloads(),
                "the placeholder substitution must be counted");
    }

    @Test
    void lateBindRunRekeysPreBindStepsUnderTheBoundId() throws Exception {
        var session = new TapeRecordingSession(recorder, new RecordingDelegate(),
                TapeRunInfo.endpoint("conv-1", "res-1", "/atmosphere/chat", "model-x", "rt"));
        // Only the run-id metadata frame from setRunId precedes the bind on
        // the real endpoint path — the recorder must tolerate it.
        session.sendMetadata("X-Atmosphere-Run-Id", "run-42");
        session.bindRun("run-42", "alice");
        session.send("hi");
        session.complete();

        var steps = awaitTerminalSteps("run-42", TapeStatus.COMPLETED);
        assertEquals("run-42", session.tapeRunId());
        assertEquals(List.of("metadata", "text", "complete"), kinds(steps),
                "the pre-bind step must be re-keyed under the bound run id, in order");
        assertTrue(steps.get(0).runId().equals("run-42"));
        var runs = store.listRuns(TapeQuery.all(0));
        assertEquals(1, runs.size(), "no orphan run under the provisional id: " + runs);
        assertEquals("alice", runs.get(0).userId());
        assertEquals("conv-1", runs.get(0).tapeId());
    }

    @Test
    void resumedWrapRecordsTheMarkerStepFirstUnderTheSameRunId() throws Exception {
        var session = new TapeRecordingSession(recorder, new RecordingDelegate(),
                TapeRunInfo.resumed("conv-2", "res-2", "/atmosphere/chat", "model-x", "rt",
                        "run-77", "alice"));
        session.send("continuation");
        session.complete();

        var steps = awaitTerminalSteps("run-77", TapeStatus.COMPLETED);
        assertEquals("run-77", session.tapeRunId());
        assertEquals(List.of("resumed", "text", "complete"), kinds(steps));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private TapeRecordingSession newPipelineSession(StreamingSession delegate) {
        return new TapeRecordingSession(recorder, delegate,
                TapeRunInfo.pipeline("client-1", "model-x", "rt"));
    }

    private void awaitStatus(String runId, TapeStatus status) throws InterruptedException {
        awaitTrue(() -> store.listRuns(TapeQuery.all(0)).stream()
                        .anyMatch(r -> r.runId().equals(runId) && r.status() == status),
                "run " + runId + " to reach " + status);
    }

    private List<TapeStep> awaitTerminalSteps(String runId, TapeStatus status)
            throws InterruptedException {
        awaitStatus(runId, status);
        return store.readSteps(runId, 0, 0);
    }

    private static void awaitTrue(BooleanSupplier condition, String what)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for " + what);
    }

    private static List<String> kinds(List<TapeStep> steps) {
        return steps.stream().map(TapeStep::kind).toList();
    }

    private static List<String> payloadsOf(List<TapeStep> steps) {
        return steps.stream().map(TapeStep::payload).toList();
    }

    /** Jackson cannot serialize this — the getter throws. */
    public static final class ThrowingBean {
        public String getValue() {
            throw new IllegalStateException("not serializable");
        }
    }

    /**
     * Minimal leaf that records which calls reached it. {@code usage} /
     * {@code confidence} are overridden so the test can assert the tape
     * forwards the TYPED records unchanged.
     */
    private static final class RecordingDelegate implements StreamingSession {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile TokenUsage usageReceived;
        volatile boolean closed;

        @Override
        public String sessionId() {
            return "leaf-session";
        }

        @Override
        public void send(String text) {
            calls.add("send:" + text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            calls.add("metadata:" + key);
        }

        @Override
        public void usage(TokenUsage usage) {
            usageReceived = usage;
            calls.add("usage");
        }

        @Override
        public void confidence(AiConfidence confidence) {
            calls.add("confidence");
        }

        @Override
        public void toolCallDelta(String toolCallId, String argsChunk) {
            calls.add("toolCallDelta:" + toolCallId);
        }

        @Override
        public void progress(String message) {
            calls.add("progress:" + message);
        }

        @Override
        public void complete() {
            closed = true;
            calls.add("complete");
        }

        @Override
        public void complete(String summary) {
            closed = true;
            calls.add("complete:" + summary);
        }

        @Override
        public void error(Throwable t) {
            closed = true;
            calls.add("error");
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    /**
     * Simulates the endpoint chain above the tape: no {@code usage} /
     * {@code confidence} overrides, so the {@link StreamingSession} interface
     * DEFAULTS convert the typed calls into {@code sendMetadata} before they
     * reach the tape — exactly what {@code AiStreamingSession} does.
     */
    private record NoOverrideForwarder(StreamingSession tape) implements StreamingSession {

        @Override
        public String sessionId() {
            return tape.sessionId();
        }

        @Override
        public void send(String text) {
            tape.send(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            tape.sendMetadata(key, value);
        }

        @Override
        public void progress(String message) {
            tape.progress(message);
        }

        @Override
        public void complete() {
            tape.complete();
        }

        @Override
        public void complete(String summary) {
            tape.complete(summary);
        }

        @Override
        public void error(Throwable t) {
            tape.error(t);
        }

        @Override
        public boolean isClosed() {
            return tape.isClosed();
        }
    }

    @Test
    void sanityDelegateStillSeesEveryForwardedCall() {
        var delegate = new RecordingDelegate();
        var session = newPipelineSession(delegate);
        session.send("a");
        session.progress("thinking");
        session.complete("done");
        assertEquals(List.of("send:a", "progress:thinking", "complete:done"), delegate.calls);
        assertNotNull(session.tapeRunId());
        assertTrue(session.isClosed());
    }
}
