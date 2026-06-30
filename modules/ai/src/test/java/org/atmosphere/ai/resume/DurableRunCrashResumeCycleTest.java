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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The end-to-end crash-resume cycle that ties the durable-execution components
 * into the one promise that distinguishes durable execution from a plain retry:
 * <em>a crashed run resumes and replays deterministically — committed LLM rounds
 * re-emit with zero provider HTTP, committed tools do not re-fire, and the run
 * reaches completion.</em>
 *
 * <p>Unlike the per-seam unit tests (round replay, tool memo, the resume engine
 * in isolation), this drives a real two-round run through the actual
 * {@link BuiltInAgentRuntime} under a {@link DurableRunSpine#beginDrive record-mode
 * scope}; abandons it <em>without</em> finalizing (a crash); then re-drives it
 * through the production {@link DurableRunResumer} — both the reconnect path
 * ({@link DurableRunResumer#resume}) and the admin path
 * ({@link DurableRunResumer#resumeAsAdmin}) — asserting the same deterministic
 * outcome (Correctness Invariant&nbsp;#7, Mode Parity). A foreign principal is
 * refused without any re-drive (Invariant&nbsp;#6).</p>
 */
// unchecked/rawtypes are unavoidable when mocking the generic HttpClient.send(...)
// and verifying it — the same suppression BuiltInRoundReplayTest uses for the
// identical streaming-mock pattern.
@SuppressWarnings({"unchecked", "rawtypes"})
class DurableRunCrashResumeCycleTest {

    private static final String RUN_ID = "run-cycle";
    private static final String USER = "alice";
    private static final String PATH = "/chat";
    private static final Duration TTL = Duration.ofMinutes(5);

    // Round 0: the model asks to call get_time("city":"Montreal").
    private static final String TOOL_CALL_ROUND = """
            data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{\\\"city\\\":\\\"Montreal\\\"}"}}]},"finish_reason":null}]}

            data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: [DONE]

            """;
    // Round 1: the model's final answer after the tool result.
    private static final String FINAL_ANSWER_ROUND = """
            data: {"id":"chatcmpl-2","model":"gpt-4","choices":[{"index":0,"delta":{"content":"It is 3pm in Montreal."},"finish_reason":null}]}

            data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":42,"completion_tokens":8,"total_tokens":50}}

            data: [DONE]

            """;

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
        ResumableEndpointRegistry.clear();
    }

    @Test
    void crashedRunResumesViaReconnectReplayingAllRoundsZeroHttpNoToolRefire() throws Exception {
        var journal = new InMemoryEffectJournal();
        var spine = enabledSpine(journal);
        var counter = new AtomicInteger();
        var tool = countingTool(counter);

        driveLiveUntilCrash(spine, tool, counter, journal);

        // Reconnect re-drive: the same spine takes the run back (the server is
        // alive; the client returned), and replays it for the reconnected client.
        var resumeHttp = silentHttp();
        var freshSession = new RunSession();
        var status = new DurableRunResumer(spine)
                .resume(RUN_ID, USER, List.of(tool), null, new DriverRuntime(client(resumeHttp)), freshSession);

        assertEquals(DurableRunResumer.Status.RESUMED, status);
        assertTrue(freshSession.completed, "the resumed run reached completion");
        assertTrue(freshSession.text().contains("Montreal"),
                "the recorded final answer is re-emitted: " + freshSession.text());
        assertEquals(1, counter.get(), "the committed tool is replayed, NOT re-executed, on resume");
        verify(resumeHttp, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void crashedRunResumesViaAdminReplayingAllRoundsZeroHttpNoToolRefire() throws Exception {
        var journal = new InMemoryEffectJournal();
        var spine = enabledSpine(journal);
        var counter = new AtomicInteger();
        var tool = countingTool(counter);

        driveLiveUntilCrash(spine, tool, counter, journal);

        // Admin re-drive: the runtime + tools are resolved from the endpoint
        // registry by the seed's recorded path (the admin caller owns no run).
        var resumeHttp = silentHttp();
        ResumableEndpointRegistry.register(PATH, new DriverRuntime(client(resumeHttp)), () -> List.of(tool));
        var freshSession = new RunSession();
        var status = new DurableRunResumer(spine).resumeAsAdmin(RUN_ID, freshSession);

        assertEquals(DurableRunResumer.Status.RESUMED, status);
        assertTrue(freshSession.completed, "the admin-resumed run reached completion");
        assertTrue(freshSession.text().contains("Montreal"),
                "the recorded final answer is re-emitted: " + freshSession.text());
        assertEquals(1, counter.get(), "the committed tool is replayed, NOT re-executed, on admin resume");
        verify(resumeHttp, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void foreignPrincipalIsRefusedAndTheRunIsNeverReDriven() throws Exception {
        var journal = new InMemoryEffectJournal();
        var spine = enabledSpine(journal);
        var counter = new AtomicInteger();
        var tool = countingTool(counter);

        driveLiveUntilCrash(spine, tool, counter, journal);

        var resumeHttp = silentHttp();
        var freshSession = new RunSession();
        var status = new DurableRunResumer(spine)
                .resume(RUN_ID, "mallory", List.of(tool), null, new DriverRuntime(client(resumeHttp)), freshSession);

        assertEquals(DurableRunResumer.Status.REFUSED, status,
                "a principal other than the run's owner cannot resume it (Inv #6)");
        assertFalse(freshSession.completed, "the run is not re-driven for a foreign principal");
        assertEquals(1, counter.get(), "the tool is never re-executed for a refused resume");
        verify(resumeHttp, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // -- helpers --

    /**
     * Drive a full two-round run (tool call then final answer) through the real
     * runtime under a record-mode scope, committing the seed + both rounds + the
     * tool effect, then simulate a crash by abandoning the run <em>without</em>
     * finalizing it: the lease stays held and the journal retains everything for a
     * re-drive, exactly as a process that died mid-run would leave it.
     */
    private void driveLiveUntilCrash(DurableRunSpine spine, ToolDefinition tool,
                                     AtomicInteger counter, InMemoryEffectJournal journal) throws Exception {
        var scope = spine.beginDrive(RUN_ID, USER, PATH);
        assertTrue(scope.isPresent(), "the spine is enabled and drives this run");

        var liveHttp = httpSequence(TOOL_CALL_ROUND, FINAL_ANSWER_ROUND);
        var liveSession = new RunSession();
        new DriverRuntime(client(liveHttp)).execute(context(List.of(tool)), liveSession);

        assertTrue(liveSession.completed, "the live run completed both rounds before the crash");
        assertEquals(1, counter.get(), "the tool ran exactly once on the live drive");
        assertTrue(journal.lookupCommitted(RUN_ID, EffectKeys.llmRound(RUN_ID, 0)).isPresent()
                        && journal.lookupCommitted(RUN_ID, EffectKeys.llmRound(RUN_ID, 1)).isPresent(),
                "both LLM rounds were committed before the crash");
        verify(liveHttp, org.mockito.Mockito.times(2))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // CRASH: the process dies before completeDrive — drop the in-JVM scope so
        // the resume path must reconstruct from the journal, not the live scope.
        DurableRunScopeHolder.remove(RUN_ID);
    }

    private static DurableRunSpine enabledSpine(InMemoryEffectJournal journal) {
        return new DurableRunSpine(journal, new DurableRunConfig(true, TTL, false), "owner");
    }

    private static ToolDefinition countingTool(AtomicInteger counter) {
        return ToolDefinition.builder("get_time", "Get current time for a city")
                .parameter("city", "The city name", "string")
                .executor(args -> {
                    counter.incrementAndGet();
                    return "3:00 PM EST";
                })
                .build();
    }

    private static AgentExecutionContext context(List<ToolDefinition> tools) {
        return new AgentExecutionContext(
                "What time is it in Montreal?", "You are a helpful assistant",
                "gpt-4", "test-agent", "session-1", USER, "conv-1",
                tools, null, null, List.of(), Map.of(), List.of(), null, null);
    }

    private static OpenAiCompatibleClient client(HttpClient httpClient) {
        return OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();
    }

    /** A client that returns the given SSE bodies in order across successive calls. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient httpSequence(String... bodies) throws Exception {
        var httpClient = mock(HttpClient.class);
        HttpResponse<java.io.InputStream>[] mocks = new HttpResponse[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            HttpResponse<java.io.InputStream> resp = mock(HttpResponse.class);
            doReturn(200).when(resp).statusCode();
            doReturn(new ByteArrayInputStream(bodies[i].getBytes(StandardCharsets.UTF_8))).when(resp).body();
            doReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true)).when(resp).headers();
            mocks[i] = resp;
        }
        var rest = java.util.Arrays.copyOfRange(mocks, 1, mocks.length);
        doReturn(mocks[0], (Object[]) rest)
                .when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return httpClient;
    }

    /**
     * A client stubbed with a benign terminal body but expected never to be
     * called — on a correct replay the resume path issues zero provider HTTP, so
     * {@code verify(never())} is the real assertion; the stub only keeps a stray
     * call from masking that with an NPE.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient silentHttp() throws Exception {
        var httpClient = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> resp = mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn(new ByteArrayInputStream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)))
                .when(resp).body();
        doReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true)).when(resp).headers();
        doReturn(resp).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return httpClient;
    }

    /** Built-in runtime wired to drive a specific (mocked) client. */
    private static final class DriverRuntime extends BuiltInAgentRuntime {
        DriverRuntime(LlmClient client) {
            setNativeClient(client);
        }
    }

    /** A session that reports {@link #RUN_ID} so the journaled seams resolve the scope. */
    private static final class RunSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private final List<AiEvent> events = new ArrayList<>();
        private volatile boolean completed;

        private String text() {
            return text.toString();
        }

        @Override
        public Optional<String> runId() {
            return Optional.of(RUN_ID);
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
        }

        @Override
        public String sessionId() {
            return "session";
        }

        @Override
        public void send(String chunk) {
            text.append(chunk);
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void complete(String summary) {
            completed = true;
        }

        @Override
        public void error(Throwable t) {
            completed = true;
        }

        @Override
        public boolean isClosed() {
            return completed;
        }
    }
}
