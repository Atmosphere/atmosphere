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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.facts.FactResolver;
import org.atmosphere.ai.facts.FactResolverHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * End-to-end regression for the cache-prefix contract at the {@code @Prompt}
 * dispatch layer: the effective system prompt the runtime receives STARTS
 * with the endpoint's stable system prompt and the volatile grounded-facts
 * block ({@code time.now} et al.) is appended at the END. The pre-fix
 * behavior prepended the fact block, which put a changing timestamp in the
 * first tokens of every request and defeated provider prompt-prefix caches
 * (Anthropic prompt caching, OpenAI/Gemini prefix caches) framework-wide.
 */
// Constructs a real AtmosphereResourceImpl via the deprecated 6-arg
// constructor — the same test harness BroadcasterTest and
// AiEndpointHandlerBroadcastReplyTest use. No non-deprecated public
// constructor builds a standalone resource for an isolated unit test.
@SuppressWarnings("deprecation")
class AiEndpointHandlerFactsCachePrefixTest {

    private static final String PERSONA =
            "You are a meticulous travel planner. Always cite sources.";

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private Method promptMethod;

    @BeforeEach
    void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/facts");
        promptMethod = StubEndpoint.class.getDeclaredMethod(
                "onPrompt", String.class, StreamingSession.class);
    }

    @AfterEach
    void tearDown() {
        FactResolverHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void effectivePromptStartsWithStablePromptAndEndsWithFactBlock() throws Exception {
        var runtime = new RecordingRuntime();
        var endpoint = new StubEndpoint();
        var handler = new AiEndpointHandler(endpoint, promptMethod, 30_000L,
                PERSONA, "/ai/facts", runtime, List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(StubEndpoint.class));
        var resource = subscriber();

        handler.onStateChange(new AtmosphereResourceEventImpl(
                (AtmosphereResourceImpl) resource).setMessage("plan a trip"));

        assertTrue(runtime.dispatched.await(10, TimeUnit.SECONDS),
                "runtime must be dispatched by the @Prompt turn");
        var effectivePrompt = runtime.lastContext.get().systemPrompt();
        assertNotNull(effectivePrompt);

        // Cache-prefix contract: stable prompt leads, byte-identical.
        assertTrue(effectivePrompt.startsWith(PERSONA),
                "effective prompt must START with the stable system prompt "
                + "(prompt-prefix cache contract): " + effectivePrompt);
        assertFalse(effectivePrompt.startsWith(
                        FactResolver.FactBundle.SYSTEM_PROMPT_BLOCK_HEADER),
                "volatile facts must never lead the prompt (the pre-fix regression)");

        // …and the volatile grounded-facts block is the absolute suffix.
        var factsIdx = effectivePrompt.indexOf(
                FactResolver.FactBundle.SYSTEM_PROMPT_BLOCK_HEADER);
        assertTrue(factsIdx > PERSONA.length(),
                "fact block must be appended after the stable prompt: " + effectivePrompt);
        var suffix = effectivePrompt.substring(factsIdx);
        assertTrue(suffix.contains("- time.now: "),
                "DefaultFactResolver must ground time.now in the trailing block: " + suffix);
        assertTrue(suffix.contains("- time.timezone: "),
                "DefaultFactResolver must ground time.timezone in the trailing block: " + suffix);
        var lines = suffix.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].isEmpty() || lines[i].startsWith("- "),
                    "nothing may follow the fact block — it must be the absolute "
                    + "suffix of the system prompt, got line: '" + lines[i] + "'");
        }
    }

    private AtmosphereResource subscriber() {
        var r = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new org.atmosphere.handler.AbstractReflectorAtmosphereHandler.Default());
        room.addAtmosphereResource(r);
        return r;
    }

    /** Records the execution context and latches when dispatched. */
    static final class RecordingRuntime implements AgentRuntime {
        final AtomicReference<AgentExecutionContext> lastContext = new AtomicReference<>();
        final CountDownLatch dispatched = new CountDownLatch(1);

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
            session.send("ok");
            session.complete();
            dispatched.countDown();
        }
    }

    @AiEndpoint(path = "/ai/facts")
    static final class StubEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            // Route the turn through the runtime — the LLM-call site — so the
            // test observes the effective system prompt the provider would see.
            session.stream(message);
        }
    }
}
