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

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.atmosphere.ai.intent.IntentRoute;
import org.atmosphere.ai.intent.IntentRouting;
import org.atmosphere.ai.intent.IntentRoutingProvider;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
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
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code @AiEndpoint(intentRouting = ...)} end to end inside the module: the
 * processor builds the provider's routing and installs it on the handler, and
 * the handler installs it on every session, so the {@code @Prompt} body's
 * {@code session.stream(message)} is routed before the runtime is reached.
 */
// Real AtmosphereResourceImpl subscribers are built through the deprecated
// 6-arg constructor, as in AiEndpointHandlerBroadcastReplyTest: no
// non-deprecated public constructor builds a standalone resource.
@SuppressWarnings("deprecation")
class AiEndpointIntentRoutingTest {

    static final IntentRouting ROUTING = IntentRouting.of("Which team handles this?",
                    IntentRoute.handler("track", "where is my parcel", d -> "tracked:" + d.message()),
                    IntentRoute.llm("general", "anything else"),
                    IntentRoute.human("agent", "a person", d -> "a person will follow up"))
            .withDecisionModel(new DecisionModel() {
                @Override public String name() { return "endpoint-test"; }
                @Override public boolean isAvailable() { return true; }
                @Override public DecisionResult decide(DecisionRequest request) {
                    var answer = new Answer.Choice(IntentRouting.QUESTION_ID, "track", Map.of(),
                            AiConfidence.reported(0.99));
                    return new DecisionResult(name(), Map.of(answer.id(), answer), Optional.empty(),
                            Duration.ZERO);
                }
            });

    /** The provider the annotation names. */
    public static final class TestRoutes implements IntentRoutingProvider {
        @Override
        public IntentRouting intentRouting() {
            return ROUTING;
        }
    }

    /** A provider whose routing cannot be built. */
    public static final class BrokenRoutes implements IntentRoutingProvider {
        @Override
        public IntentRouting intentRouting() {
            throw new IllegalStateException("routes misconfigured");
        }
    }

    @AiEndpoint(path = "/atmosphere/intent", intentRouting = TestRoutes.class)
    public static class RoutedEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.stream(message);
        }
    }

    @AiEndpoint(path = "/atmosphere/intent-broken", intentRouting = BrokenRoutes.class)
    public static class BrokenEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.stream(message);
        }
    }

    @AiEndpoint(path = "/atmosphere/plain")
    public static class PlainEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.stream(message);
        }
    }

    // --- processor ---------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"}) // AnnotationHandler.handle takes Class<Object>
    void annotationInstallsTheProvidersRoutingOnTheHandler() throws Exception {
        var framework = mock(AtmosphereFramework.class);
        when(framework.newClassInstance(eq(Object.class), any())).thenReturn(new RoutedEndpoint());
        when(framework.newClassInstance(eq(IntentRoutingProvider.class), eq(TestRoutes.class)))
                .thenReturn(new TestRoutes());

        new AiEndpointProcessor().handle(framework, (Class) RoutedEndpoint.class);

        var handler = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(eq("/atmosphere/intent"), handler.capture(), any(List.class));
        assertSame(ROUTING, ((AiEndpointHandler) handler.getValue()).intentRouting());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void withoutTheAttributeNoRoutingIsInstalled() throws Exception {
        var framework = mock(AtmosphereFramework.class);
        when(framework.newClassInstance(eq(Object.class), any())).thenReturn(new PlainEndpoint());

        new AiEndpointProcessor().handle(framework, (Class) PlainEndpoint.class);

        var handler = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(eq("/atmosphere/plain"), handler.capture(), any(List.class));
        assertNull(((AiEndpointHandler) handler.getValue()).intentRouting());
        verify(framework, never()).newClassInstance(eq(IntentRoutingProvider.class), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aProviderThatFailsKeepsTheEndpointUnregistered() throws Exception {
        var framework = mock(AtmosphereFramework.class);
        when(framework.newClassInstance(eq(Object.class), any())).thenReturn(new BrokenEndpoint());
        when(framework.newClassInstance(eq(IntentRoutingProvider.class), eq(BrokenRoutes.class)))
                .thenReturn(new BrokenRoutes());

        new AiEndpointProcessor().handle(framework, (Class) BrokenEndpoint.class);

        // Serving the endpoint without the routing it declared would send
        // every request to the LLM: fail the registration instead.
        verify(framework, never()).addAtmosphereHandler(anyString(), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    void openAiAndBatchSurfacesShareTheEndpointsRouting() {
        // Both registrars build their pipeline through servingPipeline(...).
        var pipeline = AiEndpointProcessor.servingPipeline(mock(AgentRuntime.class), "", null, null,
                null, List.of(), List.of(), List.of(), org.atmosphere.ai.AiMetrics.NOOP, null,
                Map.of(), null, ROUTING);
        assertSame(ROUTING, pipeline.defaultIntentRouting());
    }

    // --- handler -> session ------------------------------------------------

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster broadcaster;

    @BeforeEach
    void setUp() {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = factory.get(DefaultBroadcaster.class, "/atmosphere/intent");
    }

    @AfterEach
    void tearDown() {
        broadcaster.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void handlerRoutesEverySessionItCreates() throws Exception {
        var frames = new CopyOnWriteArrayList<String>();
        var done = new CountDownLatch(1);
        var capture = new AtmosphereHandler() {
            @Override public void onRequest(AtmosphereResource r) { }
            @Override public void onStateChange(AtmosphereResourceEvent e) {
                var json = e.getMessage() instanceof RawMessage raw
                        ? String.valueOf(raw.message()) : String.valueOf(e.getMessage());
                frames.add(json);
                if (json.contains("\"complete\"")) {
                    done.countDown();
                }
            }
            @Override public void destroy() { }
        };
        var resource = new AtmosphereResourceImpl(config, broadcaster,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), capture);
        broadcaster.addAtmosphereResource(resource);

        var runtime = mock(AgentRuntime.class);
        var handler = new AiEndpointHandler(new RoutedEndpoint(),
                RoutedEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", "/atmosphere/intent", runtime, List.<AiInterceptor>of(), null,
                AnnotatedLifecycle.scan(RoutedEndpoint.class));
        handler.setIntentRouting(ROUTING);

        handler.onStateChange(new AtmosphereResourceEventImpl(resource).setMessage("where is order 7?"));

        assertTrue(done.await(10, TimeUnit.SECONDS), "the routed turn must complete: " + frames);
        assertTrue(frames.stream().anyMatch(f -> f.contains("tracked:where is order 7?")), frames.toString());
        assertTrue(frames.stream().anyMatch(f -> f.contains(IntentRouting.ROUTE_METADATA_KEY)
                && f.contains("track")), frames.toString());
        verify(runtime, never()).executeWithHandle(any(), any());
        verify(runtime, never()).execute(any(), any());
        assertNotNull(handler.intentRouting());
    }
}
