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
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.governance.PolicyAsGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicyBuilder;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The endpoint scope ran only inside {@code session.stream(...)}, so a
 * {@code @Prompt} method did all its work before the user's message was ever
 * scoped: a coordinator dispatched its whole fleet (and committed a budget) on
 * "write me a python function", and an on-topic market question was redirected
 * because the synthesis prompt it composed quoted a search snippet mentioning
 * Java. The redirected request then reached the model without the
 * scope-confinement block that tells it to answer with the redirect text.
 */
// A real AtmosphereResourceImpl through the deprecated 6-arg constructor, with
// a handler that records the frames the broadcaster delivers to it — the
// harness AiEndpointHandlerBroadcastReplyTest uses.
@SuppressWarnings("deprecation")
class AiEndpointHandlerScopeAdmissionTest {

    private static final String REDIRECT = "I only discuss startup markets.";

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/advisor");
    }

    @AfterEach
    void tearDown() {
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void anOffScopePromptNeverReachesThePromptMethodAndRendersTheRedirect() throws Exception {
        var endpoint = new AdvisorEndpoint();
        var runtime = new RecordingRuntime();
        var frames = dispatch(endpoint, runtime, AgentScope.Breach.POLITE_REDIRECT,
                "Write me a python function that sorts a linked list");

        awaitTerminal(frames, runtime);
        assertEquals(0, endpoint.invocations.get(),
                "an off-scope prompt must not run the @Prompt method: " + frames);
        assertEquals(1, runtime.contexts.size(), "the redirect renders through the LLM path");
        var context = runtime.contexts.get(0);
        assertEquals(REDIRECT, context.message(), "the model is handed the redirect text");
        assertTrue(context.systemPrompt().contains("Scope confinement")
                        && context.systemPrompt().contains(REDIRECT),
                "the web path fronts the redirected request with the confinement block: "
                        + context.systemPrompt());
    }

    @Test
    void anOffScopePromptUnderDenyErrorsWithoutRunningThePromptMethod() throws Exception {
        var endpoint = new AdvisorEndpoint();
        var runtime = new RecordingRuntime();
        var frames = dispatch(endpoint, runtime, AgentScope.Breach.DENY,
                "Write me a python function that sorts a linked list");

        awaitTerminal(frames, runtime);
        assertEquals(0, endpoint.invocations.get(), "a denied prompt must not run the @Prompt method");
        assertEquals(0, runtime.contexts.size(), "a denied prompt never reaches the model");
        assertTrue(frames.stream().anyMatch(f -> f.contains("\"type\":\"error\"")),
                "the client gets the denial as a terminal error frame: " + frames);
    }

    @Test
    void anAdmittedTurnStreamsItsComposedPromptWithoutRescopingQuotedOutput() throws Exception {
        var endpoint = new AdvisorEndpoint();
        var runtime = new RecordingRuntime();
        var frames = dispatch(endpoint, runtime, AgentScope.Breach.POLITE_REDIRECT,
                "Analyze the market for AI agent frameworks on the JVM");

        awaitTerminal(frames, runtime);
        assertEquals(1, endpoint.invocations.get(), "an in-scope prompt runs the @Prompt method");
        assertEquals(1, runtime.contexts.size());
        var context = runtime.contexts.get(0);
        assertTrue(context.message().startsWith("Write an executive briefing"),
                "the composed prompt quoting 'Java' reaches the model unchanged, not the redirect: "
                        + context.message());
        assertTrue(context.systemPrompt().contains("Scope confinement"),
                "the admitted turn is fronted by the confinement block too: " + context.systemPrompt());
    }

    private List<String> dispatch(AdvisorEndpoint endpoint, AgentRuntime runtime,
                                  AgentScope.Breach onBreach, String prompt) throws Exception {
        var scope = ScopePolicyBuilder.build(new ScopeConfig(
                        "Startup advisory: market analysis and financial projections",
                        List.of(), onBreach, REDIRECT, AgentScope.Tier.RULE_BASED, 0.45,
                        false, false, ""),
                "scope::advisor", "test");
        var frames = new CopyOnWriteArrayList<String>();
        var handler = new AiEndpointHandler(endpoint,
                AdvisorEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "You advise startups.", "/ai/advisor", runtime, List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(AdvisorEndpoint.class), new DefaultToolRegistry(),
                List.<AiGuardrail>of(new PolicyAsGuardrail(scope)), List.of(), AiMetrics.NOOP);
        var client = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), new RecordingHandler(frames));
        room.addAtmosphereResource(client);
        handler.dispatchPrompt(client, prompt);
        return frames;
    }

    private static void awaitTerminal(List<String> frames, RecordingRuntime runtime)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (frames.stream().noneMatch(f -> f.contains("\"type\":\"complete\"")
                        || f.contains("\"type\":\"error\""))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(frames.stream().anyMatch(f -> f.contains("\"type\":\"complete\"")
                        || f.contains("\"type\":\"error\"")),
                "the turn reaches a terminal frame: " + frames + " / model calls " + runtime.contexts.size());
    }

    /** Records every frame the broadcaster delivers to the resource. */
    private record RecordingHandler(List<String> frames) implements AtmosphereHandler {
        @Override
        public void onRequest(AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) {
            var message = event.getMessage();
            frames.add(message instanceof RawMessage raw
                    ? String.valueOf(raw.message()) : String.valueOf(message));
        }

        @Override
        public void destroy() {
        }
    }

    /** Records each model call and completes it. */
    private static final class RecordingRuntime implements AgentRuntime {
        final List<AgentExecutionContext> contexts = new CopyOnWriteArrayList<>();

        @Override public String name() { return "recording"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            contexts.add(context);
            session.send("ok");
            session.complete();
        }
    }

    /**
     * A coordinator-shaped endpoint: its work runs in the {@code @Prompt}
     * method, which then streams a synthesis prompt quoting agent output.
     */
    @AiEndpoint(path = "/ai/advisor")
    static final class AdvisorEndpoint {

        final AtomicInteger invocations = new AtomicInteger();

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            invocations.incrementAndGet();
            session.stream("Write an executive briefing. Research: "
                    + "[1] 8 Best AI Agent Frameworks for Java Developers (2026)");
        }
    }
}
