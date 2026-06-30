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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.resume.DurableRunContext;
import org.atmosphere.ai.resume.DurableRunScopeHolder;
import org.atmosphere.ai.resume.EffectKeys;
import org.atmosphere.ai.resume.EffectKind;
import org.atmosphere.ai.resume.EffectRecord;
import org.atmosphere.ai.resume.InMemoryEffectJournal;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the BuiltIn LLM-round replay seam: a live round is recorded
 * (assistant text + tool calls + usage), and a recorded run replays with
 * <em>zero</em> provider HTTP — re-emitting the recorded text and resolving its
 * tool calls from the memo, so a crash-resumed run reconstructs deterministically
 * without re-charging the model.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class BuiltInRoundReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN_ID = "r1";

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
    }

    @Test
    void liveRoundRecordsAssistantText() throws Exception {
        var journal = new InMemoryEffectJournal();
        installScope(journal, false);
        var httpClient = mockHttpClient(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello world\"}}]}\n\ndata: [DONE]\n\n");
        var client = client(httpClient);
        var session = new CapturingSession();

        client.streamChatCompletion(
                ChatCompletionRequest.builder("m").user("hi").build(), session);

        assertEquals("hello world", session.text(), "live text streamed to the client");
        assertTrue(session.completed);
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        var recorded = journal.lookupCommitted(RUN_ID, EffectKeys.llmRound(RUN_ID, 0));
        assertTrue(recorded.isPresent(), "the round is committed for replay");
        var round = MAPPER.readValue(recorded.get().resultPayload(),
                EffectRecord.RecordedRound.class);
        assertEquals("hello world", round.assistantText());
    }

    @Test
    void recordedTerminalRoundReplaysWithZeroHttp() throws Exception {
        var journal = new InMemoryEffectJournal();
        recordRound(journal, 0, new EffectRecord.RecordedRound(
                "recorded answer", List.of(), TokenUsage.of(1, 2)));
        installScope(journal, true);
        var httpClient = mockHttpClient(200, "data: [DONE]\n\n");
        var client = client(httpClient);
        var session = new CapturingSession();

        client.streamChatCompletion(
                ChatCompletionRequest.builder("m").user("hi").build(), session);

        assertEquals("recorded answer", session.text(), "recorded text re-emitted");
        assertTrue(session.completed);
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void fullMultiRoundRunReplaysWithZeroHttpAndNoToolExecution() throws Exception {
        var journal = new InMemoryEffectJournal();
        var toolArgs = Map.<String, Object>of("x", "v");
        // Round 0: the model asked to call echo("x":"v").
        recordRound(journal, 0, new EffectRecord.RecordedRound("",
                List.of(new ChatMessage.ToolCall("call_1", "echo", "{\"x\":\"v\"}")),
                TokenUsage.of(3, 4)));
        // The tool call's recorded outcome (occurrence 0).
        var toolKey = EffectKeys.toolCall(RUN_ID, "echo", toolArgs, 0);
        // The scope below uses the 4-arg context, so its principal defaults to
        // the lease owner "owner"; the recorded digest must bind the same one.
        var toolDigest = EffectKeys.sha256Hex("echo", EffectKeys.canonicalJson(toolArgs), "owner");
        journal.appendPending(RUN_ID, EffectKind.TOOL_CALL, toolKey, toolDigest);
        journal.commit(RUN_ID, toolKey, "tool-said-hi");
        // Round 1: the model's final answer after the tool result.
        recordRound(journal, 1, new EffectRecord.RecordedRound(
                "final answer", List.of(), TokenUsage.of(5, 6)));

        installScope(journal, true);
        var httpClient = mockHttpClient(200, "data: [DONE]\n\n");
        var client = client(httpClient);
        var session = new CapturingSession();
        var executor = new CountingExecutor();
        var echo = new ToolDefinition("echo", "echo the input", List.of(), "string",
                executor, null, 0);

        client.streamChatCompletion(
                ChatCompletionRequest.builder("m").user("hi").tools(List.of(echo)).build(), session);

        assertEquals("final answer", session.text(),
                "the run reconstructs the final answer across two recorded rounds");
        assertTrue(session.completed);
        assertEquals(0, executor.calls.get(), "the tool executor is never run on replay");
        var frames = session.frames();
        assertTrue(frames.contains("ToolStart") && frames.contains("ToolResult"),
                "the recorded tool round re-emits both tool frames");
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void liveThenReplaySameRunIsDeterministicWithOneHttpCall() throws Exception {
        var journal = new InMemoryEffectJournal();
        var httpClient = mockHttpClient(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"the answer\"}}]}\n\ndata: [DONE]\n\n");
        var client = client(httpClient);

        installScope(journal, false);
        var live = new CapturingSession();
        client.streamChatCompletion(ChatCompletionRequest.builder("m").user("hi").build(), live);

        installScope(journal, true);
        var replay = new CapturingSession();
        client.streamChatCompletion(ChatCompletionRequest.builder("m").user("hi").build(), replay);

        assertEquals("the answer", live.text());
        assertEquals(live.text(), replay.text(), "replay reproduces the live output");
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // -- helpers --

    private static void installScope(InMemoryEffectJournal journal, boolean replay) {
        DurableRunScopeHolder.install(RUN_ID,
                new DurableRunContext(RUN_ID, journal, replay, "owner"));
    }

    private static void recordRound(InMemoryEffectJournal journal, int round,
                                    EffectRecord.RecordedRound payload) {
        var key = EffectKeys.llmRound(RUN_ID, round);
        journal.appendPending(RUN_ID, EffectKind.LLM_ROUND, key, "digest");
        journal.commit(RUN_ID, key, MAPPER.writeValueAsString(payload));
    }

    private static OpenAiCompatibleClient client(HttpClient httpClient) {
        return OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();
    }

    private static HttpClient mockHttpClient(int statusCode, String body) throws Exception {
        var httpClient = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return httpClient;
    }

    /** A {@link ToolExecutor} that counts invocations; must stay at 0 on replay. */
    private static final class CountingExecutor implements ToolExecutor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Object execute(Map<String, Object> arguments) {
            calls.incrementAndGet();
            return "SHOULD-NOT-APPEAR";
        }
    }

    /** A {@link StreamingSession} that carries the run id and captures output. */
    private static final class CapturingSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private final List<AiEvent> events = new ArrayList<>();
        private boolean completed;

        private String text() {
            return text.toString();
        }

        private List<String> frames() {
            var out = new ArrayList<String>();
            for (var e : events) {
                if (e instanceof AiEvent.ToolStart) {
                    out.add("ToolStart");
                } else if (e instanceof AiEvent.ToolResult) {
                    out.add("ToolResult");
                }
            }
            return out;
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
        public void send(String text) {
            this.text.append(text);
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
        }

        @Override
        public boolean isClosed() {
            return completed;
        }
    }
}
