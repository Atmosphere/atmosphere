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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutor;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the crash-resume engine end to end: a partially recorded run is re-driven
 * straight from its journal seed with <em>zero</em> provider HTTP and no tool
 * re-execution, a resume by a foreign principal is refused (Inv #6), and a run
 * with no seed is not resumable. This is the consumer that makes the recorded
 * effects of slices 5–7 a crash-durable feature rather than within-process memo.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DurableRunResumeTest {

    private static final String RUN_ID = "run-resume";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Map<String, Object> TOOL_ARGS = Map.of("x", "v");

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
    }

    private static DurableRunSpine spine(EffectJournal journal) {
        // retainOnSuccess true so the completed re-drive's history stays inspectable.
        return new DurableRunSpine(journal, new DurableRunConfig(true, TTL, true), "proc-resumer");
    }

    /** Record a complete two-round run for principal {@code userId}: seed, a tool round, a final round. */
    private static void recordCompletedRun(EffectJournal journal, String userId) {
        var seed = new EffectRecord.RunSeed("m",
                List.of(ChatMessage.system("be helpful"), ChatMessage.user("hi")),
                "tools-digest", null, null, userId, "/chat");
        var seedKey = EffectKeys.runInput(RUN_ID);
        journal.appendPending(RUN_ID, EffectKind.RUN_INPUT, seedKey, "seed-digest");
        journal.commit(RUN_ID, seedKey, RunSeeds.serialize(seed));

        // Round 0: the model asked to call echo.
        var round0Key = EffectKeys.llmRound(RUN_ID, 0);
        journal.appendPending(RUN_ID, EffectKind.LLM_ROUND, round0Key, "r0");
        journal.commit(RUN_ID, round0Key, roundJson("",
                List.of(new ChatMessage.ToolCall("call_1", "echo", "{\"x\":\"v\"}"))));
        // The tool outcome — digest binds the principal (slice 7).
        var toolKey = EffectKeys.toolCall(RUN_ID, "echo", TOOL_ARGS, 0);
        var toolDigest = EffectKeys.sha256Hex("echo", EffectKeys.canonicalJson(TOOL_ARGS), userId);
        journal.appendPending(RUN_ID, EffectKind.TOOL_CALL, toolKey, toolDigest);
        journal.commit(RUN_ID, toolKey, "tool-said-hi");
        // Round 1: the final answer after the tool result.
        var round1Key = EffectKeys.llmRound(RUN_ID, 1);
        journal.appendPending(RUN_ID, EffectKind.LLM_ROUND, round1Key, "r1");
        journal.commit(RUN_ID, round1Key, roundJson("final answer", List.of()));
    }

    @Test
    void resumesACompletedRunFromJournalWithZeroHttpAndNoToolExecution() throws Exception {
        var journal = new InMemoryEffectJournal();
        recordCompletedRun(journal, "alice");
        var spine = spine(journal);

        var outcome = spine.beginResume(RUN_ID, "alice");
        assertInstanceOf(DurableRunSpine.ResumeOutcome.Resume.class, outcome);
        var resume = (DurableRunSpine.ResumeOutcome.Resume) outcome;
        assertTrue(resume.context().replayMode(), "the resume scope is in replay mode");

        // Reconstruct the request from the seed plus the live tool set, exactly as
        // the reconnect path does, and re-drive.
        var httpClient = mockHttpClient();
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1").httpClient(httpClient).build();
        var executor = new CountingExecutor();
        var echo = new ToolDefinition("echo", "echo", List.of(), "string", executor, null, 0);
        var request = ChatCompletionRequest.builder(resume.seed().model())
                .messages(resume.seed().messages()).tools(List.of(echo)).build();
        var session = new ResumeSession();

        client.streamChatCompletion(request, session);
        spine.completeDrive(resume.context(), true);

        assertEquals("final answer", session.text(),
                "the crashed run is reconstructed to its final answer from the journal");
        assertEquals(0, executor.calls.get(), "no tool is re-executed on resume");
        assertTrue(session.frames().contains("ToolStart") && session.frames().contains("ToolResult"),
                "the recorded tool round re-emits its frames");
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertNull(DurableRunScopeHolder.get(RUN_ID), "completeDrive removes the scope");
        assertTrue(journal.claimLease(RUN_ID, "other", TTL), "completeDrive releases the lease");
    }

    @Test
    void refusesResumeForADifferentPrincipal() {
        var journal = new InMemoryEffectJournal();
        recordCompletedRun(journal, "alice");

        var outcome = spine(journal).beginResume(RUN_ID, "bob");

        assertInstanceOf(DurableRunSpine.ResumeOutcome.Refused.class, outcome);
        assertNull(DurableRunScopeHolder.get(RUN_ID), "no scope is installed for a refused resume");
        assertTrue(journal.claimLease(RUN_ID, "other", TTL),
                "the lease is released on refusal, never stranded");
    }

    @Test
    void noneWhenThereIsNoSeed() {
        var journal = new InMemoryEffectJournal();
        // A run id the journal has never seen.
        var outcome = spine(journal).beginResume(RUN_ID, "alice");

        assertInstanceOf(DurableRunSpine.ResumeOutcome.None.class, outcome);
        assertTrue(journal.claimLease(RUN_ID, "other", TTL), "no lease is stranded when nothing resumes");
    }

    @Test
    void noneWhenDurableRunsDisabled() {
        var outcome = DurableRunSpine.disabled().beginResume(RUN_ID, "alice");
        assertInstanceOf(DurableRunSpine.ResumeOutcome.None.class, outcome);
    }

    // -- helpers --

    private static String roundJson(String text, List<ChatMessage.ToolCall> toolCalls) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(
                new EffectRecord.RecordedRound(text, toolCalls, TokenUsage.of(1, 1)));
    }

    private static HttpClient mockHttpClient() throws Exception {
        var httpClient = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                new ByteArrayInputStream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return httpClient;
    }

    private static final class CountingExecutor implements ToolExecutor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Object execute(Map<String, Object> arguments) {
            calls.incrementAndGet();
            return "SHOULD-NOT-RUN";
        }
    }

    private static final class ResumeSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private final List<String> frames = new ArrayList<>();
        private boolean completed;

        private String text() {
            return text.toString();
        }

        private List<String> frames() {
            return frames;
        }

        @Override
        public Optional<String> runId() {
            return Optional.of(RUN_ID);
        }

        @Override
        public void emit(AiEvent event) {
            if (event instanceof AiEvent.ToolStart) {
                frames.add("ToolStart");
            } else if (event instanceof AiEvent.ToolResult) {
                frames.add("ToolResult");
            }
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
