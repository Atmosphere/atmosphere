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

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.CompactionConfig;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.ModelWindowCatalog;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenWindowCompaction;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins that a {@code token-window} compaction budget is sized to the model the
 * endpoint actually talks to.
 *
 * <p>{@link CompactionConfig#resolve(AtmosphereConfig, String)} shipped with a
 * model-aware branch, but every production call site used the single-argument
 * overload, so the model argument was always {@code null} and the budget fell
 * back to the application-wide {@code LLM_MODEL}. An endpoint declaring
 * {@code @AiEndpoint(model = "…")} therefore got the *other* model's context
 * window — for a 128k model on a 1M-configured app, a budget roughly eight times
 * too large, which turns a conversation that should have been compacted into a
 * provider-side context-length error. The catalog was consulted; it was just
 * consulted with the wrong model.</p>
 */
class AiEndpointCompactionBudgetTest {

    private AiEndpointProcessor processor;
    private AtmosphereFramework framework;
    private AtmosphereConfig config;

    @BeforeEach
    void setUp() {
        processor = new AiEndpointProcessor();
        framework = mock(AtmosphereFramework.class);
        config = mock(AtmosphereConfig.class);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.getInitParameter(CompactionConfig.STRATEGY_KEY))
                .thenReturn(CompactionConfig.TOKEN_WINDOW);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // framework.handle takes a raw Class
    private AiEndpointHandler register(Class<?> endpoint, Object instance) throws Exception {
        when(framework.newClassInstance(eq(Object.class), any())).thenReturn(instance);
        processor.handle(framework, (Class) endpoint);
        var captor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        org.mockito.Mockito.verify(framework)
                .addAtmosphereHandler(anyString(), captor.capture(), any(List.class));
        return (AiEndpointHandler) captor.getValue();
    }

    private static int budgetOf(AiEndpointHandler handler) {
        var memory = assertInstanceOf(InMemoryConversationMemory.class, handler.memory(),
                "the endpoint must wire in-memory conversation memory for this test");
        var strategy = assertInstanceOf(TokenWindowCompaction.class, memory.compactionStrategy(),
                "token-window must resolve to TokenWindowCompaction");
        return strategy.budgetTokens();
    }

    @Test
    void theEndpointsOwnModelSizesTheBudget() throws Exception {
        // The application is configured for one model; the endpoint declares another.
        when(config.getInitParameter(org.atmosphere.ai.AiConfig.LLM_MODEL))
                .thenReturn("gemini-2.5-flash");

        var handler = register(EndpointWithOwnModel.class, new EndpointWithOwnModel());

        var expected = ModelWindowCatalog.contextWindow(config, "gpt-4o");
        assertEquals(expected, budgetOf(handler),
                "the budget must come from the endpoint's declared model, not the "
                        + "application-wide LLM_MODEL");
    }

    @Test
    void theEndpointsModelAndTheApplicationModelActuallyDiffer() {
        // Guards the test above from passing vacuously: if these two windows were
        // equal, the assertion could not tell a threaded model from a dropped one.
        assertNotEquals(ModelWindowCatalog.contextWindow(config, "gemini-2.5-flash"),
                ModelWindowCatalog.contextWindow(config, "gpt-4o"),
                "this fixture only proves anything while the two models have "
                        + "different context windows — pick different models if the "
                        + "catalog changes");
    }

    @Test
    void anEndpointWithNoModelFallsBackToTheConfiguredOne() throws Exception {
        when(config.getInitParameter(org.atmosphere.ai.AiConfig.LLM_MODEL))
                .thenReturn("gemini-2.5-flash");

        var handler = register(EndpointWithoutModel.class, new EndpointWithoutModel());

        assertEquals(ModelWindowCatalog.contextWindow(config, "gemini-2.5-flash"),
                budgetOf(handler),
                "with no endpoint-level model the application default must still apply");
    }

    @AiEndpoint(path = "/atmosphere/budget-own-model", model = "gpt-4o",
            conversationMemory = true, requires = {AiCapability.TEXT_STREAMING})
    static class EndpointWithOwnModel {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.complete();
        }
    }

    @AiEndpoint(path = "/atmosphere/budget-no-model",
            conversationMemory = true, requires = {AiCapability.TEXT_STREAMING})
    static class EndpointWithoutModel {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.complete();
        }
    }
}
