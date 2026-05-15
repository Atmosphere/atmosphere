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
package org.atmosphere.ai;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.atmosphere.ai.llm.ChatMessage;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AiStreamingSession}.
 */
public class AiStreamingSessionTest {

    private StreamingSession delegate;
    private AtmosphereResource resource;

    @BeforeEach
    public void setUp() {
        delegate = mock(StreamingSession.class);
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
    }

    @Test
    public void testStreamDelegatesToAiSupport() {
        var aiSupport = new RecordingRuntime();

        var session = new AiStreamingSession(delegate, aiSupport,
                "You are helpful", null, List.of(), resource);

        session.stream("Hello");
        session.close();

        assertEquals(1, aiSupport.requests.size());
        assertEquals("Hello", aiSupport.requests.get(0).message());
        assertEquals("You are helpful", aiSupport.requests.get(0).systemPrompt());
    }

    @Test
    public void testPreProcessModifiesRequest() {
        var aiSupport = new RecordingRuntime();
        var interceptor = new AiInterceptor() {
            @Override
            public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
                return request.withMessage("[augmented] " + request.message());
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(interceptor), resource);

        session.stream("Hello");
        session.close();

        assertEquals("[augmented] Hello", aiSupport.requests.get(0).message());
    }

    @Test
    public void testPostProcessCalledAfterStream() {
        var aiSupport = new RecordingRuntime();
        var postProcessed = new ArrayList<String>();
        var interceptor = new AiInterceptor() {
            @Override
            public void postProcess(AiRequest request, AtmosphereResource resource) {
                postProcessed.add(request.message());
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(interceptor), resource);

        session.stream("Hello");
        session.close();

        assertEquals(1, postProcessed.size());
        assertEquals("Hello", postProcessed.get(0));
    }

    @Test
    public void testInterceptorChainFifoPreLifoPost() {
        var aiSupport = new RecordingRuntime();
        var order = new ArrayList<String>();

        var first = new AiInterceptor() {
            @Override
            public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
                order.add("pre-first");
                return request;
            }

            @Override
            public void postProcess(AiRequest request, AtmosphereResource resource) {
                order.add("post-first");
            }
        };

        var second = new AiInterceptor() {
            @Override
            public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
                order.add("pre-second");
                return request;
            }

            @Override
            public void postProcess(AiRequest request, AtmosphereResource resource) {
                order.add("post-second");
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(first, second), resource);

        session.stream("Hello");
        session.close();

        // Pre: FIFO, Post: LIFO
        assertEquals(List.of("pre-first", "pre-second", "post-second", "post-first"), order);
    }

    @Test
    public void testPreProcessErrorStopsChain() {
        var aiSupport = new RecordingRuntime();
        var interceptor = new AiInterceptor() {
            @Override
            public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
                throw new RuntimeException("guardrail violation");
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(interceptor), resource);

        session.stream("Hello");
        session.close();

        // AiSupport should NOT be called
        assertTrue(aiSupport.requests.isEmpty());
        // Error should be forwarded to delegate
        verify(delegate).error(any(RuntimeException.class));
    }

    @Test
    public void testDelegateMethods() {
        var aiSupport = new RecordingRuntime();
        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(), resource);

        when(delegate.sessionId()).thenReturn("test-id");
        when(delegate.isClosed()).thenReturn(false);

        assertEquals("test-id", session.sessionId());
        assertFalse(session.isClosed());

        session.send("streaming-text");
        verify(delegate).send("streaming-text");

        session.sendMetadata("key", "value");
        verify(delegate).sendMetadata("key", "value");

        session.progress("thinking");
        verify(delegate).progress("thinking");

        session.complete();
        verify(delegate).complete();

        session.complete("summary");
        verify(delegate).complete("summary");

        session.error(new RuntimeException("fail"));
        verify(delegate).error(any(RuntimeException.class));

        session.close();
    }

    @Test
    public void testStreamWithNoInterceptors() {
        var aiSupport = new RecordingRuntime();
        var session = new AiStreamingSession(delegate, aiSupport,
                "system", null, null, resource);

        session.stream("Hello");


        assertEquals(1, aiSupport.requests.size());
        assertEquals("system", session.systemPrompt());

        session.close();
    }

    @Test
    public void testAiRequestRecord() {
        var request = new AiRequest("msg", "sys", "gpt-4",
                null, null, null, null, Map.of("temp", 0.5), List.of());

        assertEquals("msg", request.message());
        assertEquals("sys", request.systemPrompt());
        assertEquals("gpt-4", request.model());
        assertEquals(0.5, request.metadata().get("temp"));
        assertTrue(request.history().isEmpty());

        var withMsg = request.withMessage("new msg");
        assertEquals("new msg", withMsg.message());
        assertEquals("sys", withMsg.systemPrompt());

        var withPrompt = request.withSystemPrompt("new sys");
        assertEquals("new sys", withPrompt.systemPrompt());
        assertEquals("msg", withPrompt.message());

        var withModel = request.withModel("claude-3");
        assertEquals("claude-3", withModel.model());

        var withMetadata = request.withMetadata(Map.of("maxStreamingTexts", 100));
        assertEquals(0.5, withMetadata.metadata().get("temp"));
        assertEquals(100, withMetadata.metadata().get("maxStreamingTexts"));
    }

    @Test
    public void testAiRequestConvenienceConstructors() {
        var simple = new AiRequest("msg");
        assertEquals("msg", simple.message());
        assertEquals("", simple.systemPrompt());
        assertNull(simple.model());
        assertTrue(simple.metadata().isEmpty());
        assertTrue(simple.history().isEmpty());

        var withSys = new AiRequest("msg", "sys");
        assertEquals("sys", withSys.systemPrompt());
        assertTrue(withSys.history().isEmpty());
    }

    @Test
    public void testAiRequestWithHistory() {
        var history = List.of(
                ChatMessage.user("prev question"),
                ChatMessage.assistant("prev answer")
        );
        var request = new AiRequest("msg").withHistory(history);

        assertEquals(2, request.history().size());
        assertEquals("prev question", request.history().get(0).content());
        assertEquals("prev answer", request.history().get(1).content());
        assertEquals("msg", request.message());
    }

    @Test
    public void testStreamWithMemoryLoadsHistory() {
        var aiSupport = new RecordingRuntime();
        var memory = new InMemoryConversationMemory();
        memory.addMessage("res-1", ChatMessage.user("prev question"));
        memory.addMessage("res-1", ChatMessage.assistant("prev answer"));

        when(resource.uuid()).thenReturn("res-1");

        var session = new AiStreamingSession(delegate, aiSupport,
                "system", null, List.of(), resource, memory);

        session.stream("new question");
        session.close();

        assertEquals(1, aiSupport.requests.size());
        var request = aiSupport.requests.get(0);
        assertEquals("new question", request.message());
        assertEquals(2, request.history().size());
        assertEquals("prev question", request.history().get(0).content());
        assertEquals("prev answer", request.history().get(1).content());
    }

    @Test
    public void testStreamWithoutMemoryHasEmptyHistory() {
        var aiSupport = new RecordingRuntime();

        var session = new AiStreamingSession(delegate, aiSupport,
                "system", null, List.of(), resource);

        session.stream("Hello");
        session.close();

        assertEquals(1, aiSupport.requests.size());
        assertTrue(aiSupport.requests.get(0).history().isEmpty());
    }

    @Test
    public void testContextProviderPipelineFiltersReranksAndPostProcesses() {
        var aiSupport = new RecordingRuntime();
        var calls = new ArrayList<String>();
        var originalDocs = List.of(
                new ContextProvider.Document("discard", "old.md", 0.1),
                new ContextProvider.Document("keep", "guide.md", 0.9,
                        Map.of("source_document", "guide.md", "chunk_index", "2",
                                "chunk_count", "4", "chunk_start", "120", "chunk_end", "240")));
        var filteredDocs = List.of(originalDocs.get(1));
        var processedDocs = List.of(new ContextProvider.Document(
                "processed keep", "guide.md#chunk-2", 0.95, originalDocs.get(1).metadata()));
        var provider = new ContextProvider() {
            @Override
            public String transformQuery(String originalQuery) {
                calls.add("transform");
                return "  " + originalQuery + "  ";
            }

            @Override
            public List<Document> retrieve(String query, int maxResults) {
                calls.add("retrieve:" + query + ":" + maxResults);
                return originalDocs;
            }

            @Override
            public List<Document> filter(String query, List<Document> documents) {
                calls.add("filter:" + documents.size());
                return filteredDocs;
            }

            @Override
            public List<Document> rerank(String query, List<Document> documents) {
                calls.add("rerank:" + documents.size());
                return documents;
            }

            @Override
            public List<Document> postProcess(String query, List<Document> documents) {
                calls.add("postProcess:" + documents.size());
                return processedDocs;
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(), resource, null, null, List.of(), List.of(provider));

        session.stream("question");

        assertEquals(List.of("transform", "retrieve:question:5", "filter:2", "rerank:1", "postProcess:1"),
                calls);
        var message = aiSupport.requests.get(0).message();
        assertTrue(message.contains("Relevant context:"));
        assertTrue(message.contains("Source: guide.md#chunk-2 (document: guide.md) chunk 2/4 chars 120-240 score 0.950"));
        assertTrue(message.contains("processed keep"));
    }

    @Test
    public void testContextProviderSkipsBlankTransformedQuery() {
        var aiSupport = new RecordingRuntime();
        var provider = new ContextProvider() {
            @Override
            public String transformQuery(String originalQuery) {
                return "   ";
            }

            @Override
            public List<Document> retrieve(String query, int maxResults) {
                throw new AssertionError("blank query should not be retrieved");
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(), resource, null, null, List.of(), List.of(provider));

        session.stream("question");

        assertEquals("question", aiSupport.requests.get(0).message());
    }

    @Test
    public void testStreamWrapsInMemoryCapturingSession() {
        // Verify that the session passed to aiSupport.stream() is a MemoryCapturingSession
        var memory = new InMemoryConversationMemory();
        when(resource.uuid()).thenReturn("res-1");

        var capturingAiSupport = new AgentRuntime() {
            @Override
            public String name() { return "capturing"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public int priority() { return 0; }

            @Override
            public void configure(AiConfig.LlmSettings settings) { }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                // Simulate LLM response
                session.send("Hello");
                session.send(" world");
                session.complete();
            }
        };

        var session = new AiStreamingSession(delegate, capturingAiSupport,
                "", null, List.of(), resource, memory);

        session.stream("Hi");

        session.close();

        // Memory should now contain the conversation
        var history = memory.getHistory("res-1");
        assertEquals(2, history.size());
        assertEquals(ChatMessage.user("Hi"), history.get(0));
        assertEquals(ChatMessage.assistant("Hello world"), history.get(1));
    }

    @Test
    public void testMetricsRecordLatencyOnComplete() {
        var metricsRecorder = new RecordingMetrics();
        var aiSupport = new AgentRuntime() {
            @Override public String name() { return "test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("Hello");
                session.complete();
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", "test-model", List.of(), resource, null,
                null, List.of(), List.of(), metricsRecorder, null);

        session.stream("Hi");


        assertTrue(metricsRecorder.latencyRecorded);
        assertEquals("test-model", metricsRecorder.latencyModel);
    }

    @Test
    public void testMetricsRecordStreamingTextUsage() {
        var metricsRecorder = new RecordingMetrics();
        var aiSupport = new AgentRuntime() {
            @Override public String name() { return "test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("Hello");
                session.sendMetadata("usage.promptStreamingTexts", 10);
                session.sendMetadata("usage.completionStreamingTexts", 5);
                session.complete();
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", "test-model", List.of(), resource, null,
                null, List.of(), List.of(), metricsRecorder, null);

        session.stream("Hi");


        assertTrue(metricsRecorder.streamingTextUsageRecorded);
        assertEquals(10, metricsRecorder.promptStreamingTexts);
        assertEquals(5, metricsRecorder.completionStreamingTexts);
    }

    @Test
    public void testMetricsRecordErrorOnFailure() {
        var metricsRecorder = new RecordingMetrics();
        var aiSupport = new AgentRuntime() {
            @Override public String name() { return "test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.error(new RuntimeException("timeout exceeded"));
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", "test-model", List.of(), resource, null,
                null, List.of(), List.of(), metricsRecorder, null);

        session.stream("Hi");


        assertTrue(metricsRecorder.errorRecorded);
        assertEquals("test-model", metricsRecorder.errorModel);
    }

    /**
     * Gap #10 regression: {@link AiStreamingSession#sendContent(Content)}
     * must forward text and binary variants to the wrapped delegate instead
     * of inheriting the throwing interface default. Without the explicit
     * override, an image/audio/file frame raised an
     * {@code UnsupportedOperationException} mid-stream, forcing handlers
     * to open a second {@code DefaultStreamingSession} as a workaround.
     */
    @Test
    public void testSendContentForwardsTextToDelegate() {
        var session = new AiStreamingSession(delegate, new RecordingRuntime(),
                "", null, List.of(), resource);

        session.sendContent(new Content.Text("hello"));

        verify(delegate).sendContent(new Content.Text("hello"));
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testSendContentForwardsImageToDelegate() {
        var session = new AiStreamingSession(delegate, new RecordingRuntime(),
                "", null, List.of(), resource);

        var image = new Content.Image(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png");
        // Expect no throw — AiStreamingSession must route binary through the
        // delegate, not inherit the interface default that dropped it to
        // metadata breadcrumbs.
        assertDoesNotThrow(() -> session.sendContent(image));
        verify(delegate).sendContent(image);
    }

    @Test
    public void testSendContentForwardsAudioToDelegate() {
        var session = new AiStreamingSession(delegate, new RecordingRuntime(),
                "", null, List.of(), resource);

        var audio = new Content.Audio("RIFF".getBytes(), "audio/wav");
        assertDoesNotThrow(() -> session.sendContent(audio));
        verify(delegate).sendContent(audio);
    }

    @Test
    public void testSendContentForwardsFileToDelegate() {
        var session = new AiStreamingSession(delegate, new RecordingRuntime(),
                "", null, List.of(), resource);

        var file = new Content.File("name,value\n1,2\n".getBytes(), "text/csv", "results.csv");
        assertDoesNotThrow(() -> session.sendContent(file));
        verify(delegate).sendContent(file);
    }

    @Test
    public void testInputAssemblyEmittedOnWebsocketPath() {
        var metricsRecorder = new RecordingMetrics();
        var aiSupport = new AgentRuntime() {
            @Override public String name() { return "test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("ok");
                session.complete();
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "you are concise", "test-model", List.of(), resource, null,
                null, List.of(), List.of(), metricsRecorder, null);

        session.stream("hello");

        // Mode parity (Invariant #7): the @AiEndpoint websocket path emits
        // the same per-stage breakdown as AiPipeline. System and user_message
        // are always present; tool/scrollback/structured/confidence stages
        // only appear when the corresponding pipeline feature was used.
        var stages = metricsRecorder.assemblyStages;
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_SYSTEM),
                "system stage missing: " + stages);
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_USER_MESSAGE),
                "user_message stage missing: " + stages);
        assertEquals("test-model", metricsRecorder.assemblyModel,
                "assembly entries should carry the runtime model label");
    }

    static class RecordingMetrics implements AiMetrics {
        boolean streamingTextUsageRecorded;
        boolean latencyRecorded;
        boolean errorRecorded;
        String latencyModel;
        String errorModel;
        int promptStreamingTexts;
        int completionStreamingTexts;
        final List<String> assemblyStages = new ArrayList<>();
        String assemblyModel;

        @Override
        public void recordStreamingTextUsage(String model, int promptStreamingTexts, int completionStreamingTexts) {
            this.streamingTextUsageRecorded = true;
            this.promptStreamingTexts = promptStreamingTexts;
            this.completionStreamingTexts = completionStreamingTexts;
        }

        @Override
        public void recordLatency(String model, Duration timeToFirstStreamingText, Duration totalDuration) {
            this.latencyRecorded = true;
            this.latencyModel = model;
        }

        @Override
        public void recordCost(String model, BigDecimal cost) { }

        @Override
        public void recordToolCall(String model, String toolName, Duration duration, boolean success) { }

        @Override
        public void recordError(String model, String errorType) {
            this.errorRecorded = true;
            this.errorModel = model;
        }

        @Override
        public void recordInputAssembly(String model, String stage,
                                        int approximateTokens, int approximateChars) {
            this.assemblyStages.add(stage);
            this.assemblyModel = model;
        }
    }

    /**
     * AiSupport that records all requests for verification.
     */
    static class RecordingRuntime implements AgentRuntime {
        final List<AgentExecutionContext> requests = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
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
            requests.add(context);
        }
    }
}
