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

import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    }

    @Test
    public void testStreamDelegatesToAiSupport() {
        var aiSupport = new RecordingAiSupport();

        var session = new AiStreamingSession(delegate, aiSupport,
                "You are helpful", null, List.of(), resource);

        session.stream("Hello");

        assertEquals(1, aiSupport.requests.size());
        assertEquals("Hello", aiSupport.requests.get(0).message());
        assertEquals("You are helpful", aiSupport.requests.get(0).systemPrompt());
    }

    @Test
    public void testPreProcessModifiesRequest() {
        var aiSupport = new RecordingAiSupport();
        var interceptor = new AiInterceptor() {
            @Override
            public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
                return request.withMessage("[augmented] " + request.message());
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(interceptor), resource);

        session.stream("Hello");

        assertEquals("[augmented] Hello", aiSupport.requests.get(0).message());
    }

    @Test
    public void testPostProcessCalledAfterStream() {
        var aiSupport = new RecordingAiSupport();
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

        assertEquals(1, postProcessed.size());
        assertEquals("Hello", postProcessed.get(0));
    }

    @Test
    public void testInterceptorChainFifoPreLifoPost() {
        var aiSupport = new RecordingAiSupport();
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

        // Pre: FIFO, Post: LIFO
        assertEquals(List.of("pre-first", "pre-second", "post-second", "post-first"), order);
    }

    @Test
    public void testPreProcessErrorStopsChain() {
        var aiSupport = new RecordingAiSupport();
        var interceptor = new AiInterceptor() {
            @Override
            public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
                throw new RuntimeException("guardrail violation");
            }
        };

        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(interceptor), resource);

        session.stream("Hello");

        // AiSupport should NOT be called
        assertTrue(aiSupport.requests.isEmpty());
        // Error should be forwarded to delegate
        verify(delegate).error(any(RuntimeException.class));
    }

    @Test
    public void testDelegateMethods() {
        var aiSupport = new RecordingAiSupport();
        var session = new AiStreamingSession(delegate, aiSupport,
                "", null, List.of(), resource);

        when(delegate.sessionId()).thenReturn("test-id");
        when(delegate.isClosed()).thenReturn(false);

        assertEquals("test-id", session.sessionId());
        assertFalse(session.isClosed());

        session.send("token");
        verify(delegate).send("token");

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
    }

    @Test
    public void testStreamWithNoInterceptors() {
        var aiSupport = new RecordingAiSupport();
        var session = new AiStreamingSession(delegate, aiSupport,
                "system", null, null, resource);

        session.stream("Hello");

        assertEquals(1, aiSupport.requests.size());
        assertEquals("system", session.systemPrompt());
    }

    @Test
    public void testAiRequestRecord() {
        var request = new AiRequest("msg", "sys", "gpt-4", Map.of("temp", 0.5));

        assertEquals("msg", request.message());
        assertEquals("sys", request.systemPrompt());
        assertEquals("gpt-4", request.model());
        assertEquals(0.5, request.hints().get("temp"));

        var withMsg = request.withMessage("new msg");
        assertEquals("new msg", withMsg.message());
        assertEquals("sys", withMsg.systemPrompt());

        var withPrompt = request.withSystemPrompt("new sys");
        assertEquals("new sys", withPrompt.systemPrompt());
        assertEquals("msg", withPrompt.message());

        var withModel = request.withModel("claude-3");
        assertEquals("claude-3", withModel.model());

        var withHints = request.withHints(Map.of("maxTokens", 100));
        assertEquals(0.5, withHints.hints().get("temp"));
        assertEquals(100, withHints.hints().get("maxTokens"));
    }

    @Test
    public void testAiRequestConvenienceConstructors() {
        var simple = new AiRequest("msg");
        assertEquals("msg", simple.message());
        assertEquals("", simple.systemPrompt());
        assertNull(simple.model());
        assertTrue(simple.hints().isEmpty());

        var withSys = new AiRequest("msg", "sys");
        assertEquals("sys", withSys.systemPrompt());
    }

    /**
     * AiSupport that records all requests for verification.
     */
    static class RecordingAiSupport implements AiSupport {
        final List<AiRequest> requests = new ArrayList<>();

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
        public void stream(AiRequest request, StreamingSession session) {
            requests.add(request);
        }
    }
}
