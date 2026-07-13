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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.cache.InMemoryResponseCache;
import org.atmosphere.ai.llm.CacheHint;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whole-feature integration coverage for the session tape, driven through the
 * REAL dispatch seams rather than a hand-built decorator stack:
 *
 * <ul>
 *   <li>Endpoint path — the tape wraps the traced leaf and becomes
 *       {@link AiStreamingSession}'s delegate, exactly as
 *       {@code AiEndpointHandler} wires it (wrap, {@code setRunId} metadata
 *       frame pre-bind, {@code bindRun}, then {@code stream} dispatch). A fake
 *       runtime drives mixed legacy + typed calls and the exact ordered tape
 *       is asserted.</li>
 *   <li>Pipeline path — {@link AiPipeline#execute} wraps at method entry, so
 *       a pre-admission guardrail denial is taped as an ERROR run and a
 *       response-cache hit turn is taped like any other turn.</li>
 *   <li>MODE PARITY — the same logical event sequence through both paths
 *       yields an identical tape modulo run ids / timestamps, pinning the
 *       typed {@code usage}/{@code confidence}/{@code toolCallDelta}
 *       normalization (Correctness Invariant #7).</li>
 *   <li>Crash-resume — the {@link TapeRunInfo#resumed} wrap appends a
 *       {@code resumed} marker segment to the SAME run id and drives the
 *       dangling OPEN run to a true terminal.</li>
 * </ul>
 */
class TapeIntegrationTest {

    private InMemoryTapeStore store;
    private TapeRecorder recorder;
    private AtmosphereResource resource;

    @BeforeEach
    void setUp() {
        store = new InMemoryTapeStore();
        recorder = TapeSupport.install(store);
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("res-int");
    }

    @AfterEach
    void tearDown() {
        TapeSupport.uninstall(recorder);
    }

    @Test
    void endpointChainTapesMixedLegacyAndTypedEventsInExactOrder() throws Exception {
        var leaf = new RecordingLeaf();
        // AiEndpointHandler wiring: the tape wraps the traced leaf so it
        // becomes AiStreamingSession's delegate and records everything
        // crossing the session boundary post-decorator.
        var traced = TapeSupport.wrap(leaf, TapeRunInfo.endpoint(
                "conv-int", "res-int", "/ai/story", "model-x", "scripted"));
        var tape = assertInstanceOf(TapeRecordingSession.class, traced);
        var runtime = new ScriptedRuntime(s -> {
            s.send("Once");
            s.send(" upon");
            s.sendMetadata("custom.note", "legacy-metadata");
            s.progress("thinking");
            s.emit(new AiEvent.TextDelta(" a time"));
            s.emit(new AiEvent.ToolStart("search", Map.of("q", "dragons")));
            s.emit(new AiEvent.ToolResult("search", "3 hits"));
            s.send("The end");
            s.usage(TokenUsage.of(10, 20, 30));
            s.complete();
        });
        // Real decorator chain: memory + a passing guardrail make stream()
        // build MemoryCapturingSession and GuardrailCapturingSession above
        // the AiStreamingSession, as production configs do.
        var session = new AiStreamingSession(traced, runtime, "system", "model-x",
                List.of(), resource, new InMemoryConversationMemory(), null,
                List.of(new AiGuardrail() { }), List.of());

        // Handler order: setRunId emits the X-Atmosphere-Run-Id metadata frame
        // through the tape (pre-bind), then bindRun re-keys it.
        session.setRunId("run-endpoint-1");
        tape.bindRun("run-endpoint-1", "alice");
        session.stream("tell me a story");

        var steps = awaitTerminalSteps("run-endpoint-1", TapeStatus.COMPLETED);
        assertEquals(List.of("metadata", "input", "metadata", "progress", "text", "tool-start",
                        "tool-result", "metadata", "metadata", "metadata", "text", "complete"),
                kinds(steps),
                "exact ordered tape expected — the input prompt recorded at dispatch, "
                        + "text coalesced, flushed at the tool boundary and at the terminal, "
                        + "typed usage normalized to ai.tokens.* metadata: " + steps);
        assertTrue(steps.get(0).payload().contains("\"key\":\"X-Atmosphere-Run-Id\""),
                "the pre-bind setRunId frame must be re-keyed under the bound run: "
                        + steps.get(0).payload());
        assertTrue(steps.get(1).payload().contains("\"messages\":[")
                        && steps.get(1).payload().contains("\"tell me a story\""),
                "the input step records the prompt (system + user) at dispatch: "
                        + steps.get(1).payload());
        assertTrue(steps.get(2).payload().contains("\"key\":\"custom.note\""),
                steps.get(2).payload());
        assertTrue(steps.get(4).payload().contains("\"text\":\"Once upon a time\""),
                "send + emit(TextDelta) must coalesce into ONE segment: "
                        + steps.get(4).payload());
        assertTrue(steps.get(5).payload().contains("\"toolName\":\"search\""),
                steps.get(5).payload());
        assertTrue(steps.get(7).payload().contains("\"key\":\"ai.tokens.input\",\"value\":10"),
                steps.get(7).payload());
        assertTrue(steps.get(8).payload().contains("\"key\":\"ai.tokens.output\",\"value\":20"),
                steps.get(8).payload());
        assertTrue(steps.get(9).payload().contains("\"key\":\"ai.tokens.total\",\"value\":30"),
                steps.get(9).payload());
        assertTrue(steps.get(10).payload().contains("\"text\":\"The end\""),
                steps.get(10).payload());

        var runs = store.listRuns(TapeQuery.byTapeId("conv-int", 0));
        assertEquals(1, runs.size(), "one endpoint run expected: " + runs);
        var run = runs.get(0);
        assertEquals("run-endpoint-1", run.runId());
        assertEquals("alice", run.userId());
        assertEquals("res-int", run.resourceUuid());
        assertEquals("/ai/story", run.endpoint());
        assertEquals("model-x", run.model());
        assertEquals("scripted", run.runtimeName());
        assertEquals(steps.size(), run.stepCount(), "stepCount is store-local runtime truth");

        // Record-then-forward: the wire saw every event untouched.
        assertTrue(leaf.calls.contains("send:Once"), leaf.calls.toString());
        assertTrue(leaf.calls.contains("metadata:custom.note"), leaf.calls.toString());
        assertTrue(leaf.calls.contains("progress:thinking"), leaf.calls.toString());
        assertTrue(leaf.calls.contains("emit:tool-start"), leaf.calls.toString());
        assertTrue(leaf.calls.contains("complete"), leaf.calls.toString());
    }

    @Test
    void pipelinePreAdmissionGuardrailDenialIsTapedAsAnErrorRun() throws Exception {
        var runtime = new ScriptedRuntime(s -> s.complete());
        AiGuardrail blocker = new AiGuardrail() {
            @Override
            public AiGuardrail.GuardrailResult inspectRequest(AiRequest request) {
                return AiGuardrail.GuardrailResult.block("forbidden content");
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "model-x",
                null, null, List.of(blocker), List.of(), AiMetrics.NOOP);
        var leaf = new RecordingLeaf();

        pipeline.execute("client-denied", "bad input", leaf);

        // The wrap happens at execute() entry, BEFORE the guardrail loop —
        // so the denial that never reaches the runtime is still a taped run.
        var run = awaitRun("client-denied", TapeStatus.ERROR);
        var steps = store.readSteps(run.runId(), 0, 0);
        assertEquals(List.of("error"), kinds(steps),
                "a pre-admission denial is a one-step ERROR tape: " + steps);
        assertTrue(steps.get(0).payload().contains("Request blocked: forbidden content"),
                steps.get(0).payload());
        assertTrue(steps.get(0).payload().contains(SecurityException.class.getName()),
                steps.get(0).payload());
        assertEquals(0, runtime.calls.get(), "the runtime must never fire on a denial");
        assertEquals("model-x", run.model());
        assertEquals("scripted", run.runtimeName());
        assertTrue(leaf.calls.contains("error"),
                "the denial must still reach the caller's session: " + leaf.calls);
    }

    @Test
    void pipelineCacheHitTurnIsTaped() throws Exception {
        var runtime = new ScriptedRuntime(s -> {
            s.send("cached answer");
            s.complete();
        });
        var pipeline = new AiPipeline(runtime, "sys", "model-x",
                null, null, List.of(), List.of(), AiMetrics.NOOP);
        pipeline.setResponseCache(new InMemoryResponseCache(8), Duration.ofMinutes(1));
        pipeline.setDefaultCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);

        // Miss turn: the runtime fires; the consulted-but-missed signal and
        // the streamed text are taped.
        pipeline.execute("client-cache", "hello world", new RecordingLeaf());
        var missRun = awaitRun("client-cache", TapeStatus.COMPLETED);
        var missSteps = store.readSteps(missRun.runId(), 0, 0);
        assertEquals(List.of("input", "metadata", "text", "complete"), kinds(missSteps),
                "miss turn tape: " + missSteps);
        assertTrue(missSteps.get(1).payload()
                        .contains("\"key\":\"ai.cache.hit\",\"value\":false"),
                missSteps.get(1).payload());

        // Hit turn: the runtime does NOT fire, yet the replayed turn is a
        // fully taped run of its own — the tape wraps at execute() entry,
        // above the cache branch.
        pipeline.execute("client-cache", "hello world", new RecordingLeaf());
        assertEquals(1, runtime.calls.get(), "second turn must be served from cache");
        var hitRun = awaitOtherRun("client-cache", missRun.runId());
        var hitSteps = store.readSteps(hitRun.runId(), 0, 0);
        assertEquals(List.of("input", "metadata", "text", "complete"), kinds(hitSteps),
                "hit turn tape: " + hitSteps);
        assertTrue(hitSteps.get(1).payload()
                        .contains("\"key\":\"ai.cache.hit\",\"value\":true"),
                hitSteps.get(1).payload());
        assertEquals(missSteps.get(0).payload(), hitSteps.get(0).payload(),
                "the recorded input prompt must be identical on the miss and the replay");
        assertEquals(missSteps.get(2).payload(), hitSteps.get(2).payload(),
                "the replayed text segment must match the miss-path response");
    }

    @Test
    void endpointAndPipelinePathsProduceAnIdenticalTapeModuloIds() throws Exception {
        // One logical event sequence, mixing legacy sends with the typed
        // calls whose arrival form DIFFERS by path: the endpoint chain
        // converts usage/confidence/toolCallDelta to ai.* metadata one hop
        // above the tape (AiStreamingSession has no typed overrides), while
        // the pipeline chain forwards them typed and the tape normalizes.
        Consumer<StreamingSession> script = s -> {
            s.send("The answer");
            s.emit(new AiEvent.TextDelta(" is 42"));
            s.toolCallDelta("call_1", "{\"expr\":");
            s.usage(TokenUsage.of(7, 11, 18));
            s.confidence(AiConfidence.reported(0.9));
            s.emit(new AiEvent.ToolStart("calc", Map.of("expr", "6*7")));
            s.send("done");
            s.complete();
        };

        // Endpoint chain.
        var traced = TapeSupport.wrap(new RecordingLeaf(), TapeRunInfo.endpoint(
                "parity-endpoint", "res-int", "/ai/parity", "model-x", "scripted"));
        assertInstanceOf(TapeRecordingSession.class, traced)
                .bindRun("run-parity-endpoint", "alice");
        new AiStreamingSession(traced, new ScriptedRuntime(script), "system", "model-x",
                List.of(), resource).stream("compute");

        // Pipeline chain.
        new AiPipeline(new ScriptedRuntime(script), "system", "model-x",
                null, null, List.of(), List.of(), AiMetrics.NOOP)
                .execute("client-parity", "compute", new RecordingLeaf());

        var endpointSteps = awaitTerminalSteps("run-parity-endpoint", TapeStatus.COMPLETED);
        var pipelineRun = awaitRun("client-parity", TapeStatus.COMPLETED);
        var pipelineSteps = store.readSteps(pipelineRun.runId(), 0, 0);

        assertEquals(kinds(endpointSteps), kinds(pipelineSteps),
                "MODE PARITY (Invariant #7): both dispatch paths must produce the "
                        + "same step kinds in the same order");
        assertEquals(payloadsOf(endpointSteps), payloadsOf(pipelineSteps),
                "MODE PARITY: identical payloads modulo run ids / timestamps "
                        + "(neither appears inside step payloads)");
        assertEquals(List.of("input", "metadata", "metadata", "metadata", "metadata", "metadata",
                        "text", "tool-start", "text", "complete"),
                kinds(pipelineSteps), "expected parity shape: " + pipelineSteps);
        // Pins the must-fix normalization: typed usage/confidence became the
        // endpoint's ai.* metadata form, and toolCallDelta was skipped in
        // BOTH its typed and metadata forms.
        var payloads = payloadsOf(pipelineSteps);
        assertTrue(payloads.stream()
                        .anyMatch(p -> p.contains("\"key\":\"ai.tokens.input\",\"value\":7")),
                "normalized usage expected: " + payloads);
        assertTrue(payloads.stream()
                        .anyMatch(p -> p.contains("\"key\":\"" + AiConfidence.AGGREGATE_METADATA_KEY
                                + "\",\"value\":0.9")),
                "normalized confidence expected: " + payloads);
        assertTrue(payloads.stream().noneMatch(p -> p.contains("ai.toolCall.delta")),
                "toolCallDelta must be skipped on both paths: " + payloads);
    }

    @Test
    void crashResumeWrapAppendsAResumedSegmentToTheSameRunId() throws Exception {
        // Turn 1 — an endpoint run that "crashes" mid-stream: steps persisted,
        // no terminal ever fires.
        var first = assertInstanceOf(TapeRecordingSession.class,
                TapeSupport.wrap(new RecordingLeaf(), TapeRunInfo.endpoint(
                        "conv-crash", "res-int", "/ai/chat", "model-x", "scripted")));
        first.bindRun("run-crash-1", "alice");
        first.send("Let me check");
        // Semantic boundary flushes the pending text before the crash.
        first.emit(new AiEvent.ToolStart("search", Map.of("q", "answer")));
        // Simulated crash: the recorder goes away without any terminal.
        // uninstall() drains synchronously, so the store state is settled here.
        TapeSupport.uninstall(recorder);
        var crashed = store.listRuns(TapeQuery.byTapeId("conv-crash", 0));
        assertEquals(1, crashed.size(), crashed.toString());
        assertEquals(TapeStatus.OPEN, crashed.get(0).status(),
                "a crashed run must dangle OPEN until resumed or swept");
        assertEquals(List.of("text", "tool-start"), kinds(store.readSteps("run-crash-1", 0, 0)));

        // Restart — a new recorder over the same store; maybeResumeCrashedRun
        // wraps with TapeRunInfo.resumed carrying the SAME run id, and begin()
        // is an idempotent upsert that never resets steps.
        recorder = TapeSupport.install(store);
        var resumed = TapeSupport.wrap(new RecordingLeaf(), TapeRunInfo.resumed(
                "conv-crash", "res-int-2", "/ai/chat", "model-x", "scripted",
                "run-crash-1", "alice"));
        // The re-drive streams the replayed output and reaches a true terminal.
        resumed.send("The answer is 42");
        resumed.complete();

        var steps = awaitTerminalSteps("run-crash-1", TapeStatus.COMPLETED);
        assertEquals(List.of("text", "tool-start", "resumed", "text", "complete"),
                kinds(steps),
                "the RESUMED marker must precede the re-driven segment: " + steps);
        assertTrue(steps.get(3).payload().contains("\"text\":\"The answer is 42\""),
                steps.get(3).payload());
        var runs = store.listRuns(TapeQuery.all(0));
        assertEquals(1, runs.size(),
                "the resume must append to the SAME run, never mint a second one: " + runs);
        assertEquals("run-crash-1", runs.get(0).runId());
        assertEquals(TapeStatus.COMPLETED, runs.get(0).status());
        assertEquals("alice", runs.get(0).userId());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<TapeStep> awaitTerminalSteps(String runId, TapeStatus status)
            throws InterruptedException {
        awaitTrue(() -> store.listRuns(TapeQuery.all(0)).stream()
                        .anyMatch(r -> r.runId().equals(runId) && r.status() == status),
                "run " + runId + " to reach " + status);
        return store.readSteps(runId, 0, 0);
    }

    private TapeRun awaitRun(String tapeId, TapeStatus status) throws InterruptedException {
        awaitTrue(() -> store.listRuns(TapeQuery.byTapeId(tapeId, 0)).stream()
                        .anyMatch(r -> r.status() == status),
                "a " + status + " run under tape " + tapeId);
        return store.listRuns(TapeQuery.byTapeId(tapeId, 0)).stream()
                .filter(r -> r.status() == status).findFirst().orElseThrow();
    }

    private TapeRun awaitOtherRun(String tapeId, String excludedRunId)
            throws InterruptedException {
        awaitTrue(() -> store.listRuns(TapeQuery.byTapeId(tapeId, 0)).stream()
                        .anyMatch(r -> !r.runId().equals(excludedRunId)
                                && r.status() == TapeStatus.COMPLETED),
                "a second COMPLETED run under tape " + tapeId);
        return store.listRuns(TapeQuery.byTapeId(tapeId, 0)).stream()
                .filter(r -> !r.runId().equals(excludedRunId))
                .findFirst().orElseThrow();
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

    /** Fake runtime replaying a scripted event sequence on whatever session it is handed. */
    private static final class ScriptedRuntime implements AgentRuntime {
        final AtomicInteger calls = new AtomicInteger();
        private final Consumer<StreamingSession> script;

        ScriptedRuntime(Consumer<StreamingSession> script) {
            this.script = script;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            calls.incrementAndGet();
            script.accept(session);
        }
    }

    /** Minimal wire leaf recording which calls reached it (delivery truth). */
    private static final class RecordingLeaf implements StreamingSession {
        final List<String> calls = new CopyOnWriteArrayList<>();
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
        public void progress(String message) {
            calls.add("progress:" + message);
        }

        @Override
        public void emit(AiEvent event) {
            calls.add("emit:" + event.eventType());
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
}
