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

import org.atmosphere.ai.facts.FactKeys;
import org.atmosphere.ai.facts.FactResolver;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the cache-prefix contract of system-prompt assembly: the STABLE text
 * (persona, then structured-output schema / confidence cue) forms the leading,
 * byte-identical prefix of the prompt the runtime receives, and the VOLATILE
 * grounded-facts block is the absolute suffix. Provider prompt-prefix caches
 * (Anthropic prompt caching, OpenAI/Gemini prefix caches) key on leading
 * tokens — a per-turn {@code time.now} fact at the front (the pre-fix
 * behavior) made every request byte-unique and zeroed cache hits framework
 * wide. Covered on both invocation modes (Mode Parity): {@link AiPipeline}
 * and {@link AiStreamingSession}.
 */
class PromptCachePrefixContractTest {

    record Guess(String answer) { }

    private static final String PERSONA = "You are a cache-prefix test persona.";

    /** Runtime that records the context it receives and completes. */
    static class RecordingRuntime implements AgentRuntime {
        final AtomicReference<AgentExecutionContext> lastContext = new AtomicReference<>();

        @Override
        public String name() { return "recording"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public int priority() { return 0; }

        @Override
        public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            lastContext.set(context);
            session.send("{\"answer\":\"ok\"}");
            session.complete();
        }
    }

    /** Minimal collecting session for the pipeline mode. */
    static class CollectingSession implements StreamingSession {
        final List<String> sent = new ArrayList<>();

        @Override
        public String sessionId() { return "test-session"; }

        @Override
        public void send(String text) { sent.add(text); }

        @Override
        public void sendMetadata(String key, Object value) { }

        @Override
        public void progress(String message) { }

        @Override
        public void complete() { }

        @Override
        public void complete(String summary) { }

        @Override
        public void error(Throwable t) { }

        @Override
        public boolean isClosed() { return false; }
    }

    private static FactResolver.FactBundle bundle() {
        return new FactResolver.FactBundle(Map.of(
                FactKeys.TIME_NOW, "2026-04-18T21:00:00Z",
                FactKeys.TIME_TIMEZONE, "UTC"));
    }

    private static void assertCachePrefixShape(String effectivePrompt, String schemaText,
                                               FactResolver.FactBundle facts) {
        assertNotNull(effectivePrompt);
        assertTrue(effectivePrompt.startsWith(PERSONA),
                "stable persona must be the byte-identical leading prefix: " + effectivePrompt);
        assertFalse(effectivePrompt.startsWith(
                        FactResolver.FactBundle.SYSTEM_PROMPT_BLOCK_HEADER),
                "volatile facts must never lead the prompt (the pre-fix regression)");
        var schemaIdx = effectivePrompt.indexOf(schemaText);
        var factsIdx = effectivePrompt.indexOf(
                FactResolver.FactBundle.SYSTEM_PROMPT_BLOCK_HEADER);
        assertTrue(schemaIdx > 0, "stable schema/cue text must be present");
        assertTrue(factsIdx > schemaIdx,
                "stable schema/cue text must stay inside the cacheable prefix, before facts");
        assertEquals(facts.asSystemPromptBlock(), effectivePrompt.substring(factsIdx),
                "grounded-facts block must be the absolute suffix");
    }

    @Test
    void pipelineModeKeepsSchemaInStablePrefixAndFactsAsSuffix() {
        var runtime = new RecordingRuntime();
        var facts = bundle();
        var pipeline = new AiPipeline(runtime, facts.appendToSystemPrompt(PERSONA), "gpt-4",
                null, null, List.of(), List.of(), AiMetrics.NOOP, Guess.class);

        pipeline.execute("client-1", "hello", new CollectingSession());

        var context = runtime.lastContext.get();
        assertNotNull(context, "runtime must have been dispatched");
        var schemaText = StructuredOutputParser.resolve().schemaInstructions(Guess.class);
        assertCachePrefixShape(context.systemPrompt(), schemaText, facts);
    }

    @Test
    void pipelineModeKeepsConfidenceCueInStablePrefixAndFactsAsSuffix() {
        var runtime = new RecordingRuntime();
        var facts = bundle();
        var pipeline = new AiPipeline(runtime, facts.appendToSystemPrompt(PERSONA), "gpt-4",
                null, null, List.of(), List.of(), AiMetrics.NOOP, null);
        var elicitation = AiConfidenceElicitation.defaults();
        pipeline.setDefaultConfidenceElicitation(elicitation);

        pipeline.execute("client-1", "hello", new CollectingSession());

        var context = runtime.lastContext.get();
        assertNotNull(context, "runtime must have been dispatched");
        assertCachePrefixShape(context.systemPrompt(), elicitation.effectiveCue(), facts);
    }

    @Test
    void streamingSessionModeKeepsSchemaInStablePrefixAndFactsAsSuffix() {
        var runtime = new RecordingRuntime();
        var facts = bundle();
        var delegate = mock(StreamingSession.class);
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);

        var session = new AiStreamingSession(delegate, runtime,
                facts.appendToSystemPrompt(PERSONA), "gpt-4",
                List.of(), resource, null, null, List.of(), List.of(),
                AiMetrics.NOOP, Guess.class);
        session.stream("hello");
        session.close();

        var context = runtime.lastContext.get();
        assertNotNull(context, "runtime must have been dispatched");
        var schemaText = StructuredOutputParser.resolve().schemaInstructions(Guess.class);
        assertCachePrefixShape(context.systemPrompt(), schemaText, facts);
    }
}
