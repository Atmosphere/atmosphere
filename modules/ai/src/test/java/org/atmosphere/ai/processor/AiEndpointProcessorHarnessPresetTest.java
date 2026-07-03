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

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.CompactionConfig;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.LlmSummarizingCompaction;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.llm.CacheHint;
import org.atmosphere.ai.llm.PromptCacheDefaults;
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;
import org.atmosphere.ai.memory.MemoryExtractionStrategy;
import org.atmosphere.ai.preset.Harness;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the harness {@code @AiEndpoint} seams end-to-end through
 * {@link AiEndpointProcessor#handle}: the conversation-memory gate, the
 * long-term-memory auto-attach (+ no-duplicate rule), the per-endpoint
 * {@code harness()} opt-in, the exclude-paths opt-out, the kill switch, the
 * prompt-cache default precedence, and the compaction seam.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class AiEndpointProcessorHarnessPresetTest {

    private AiEndpointProcessor processor;
    private AtmosphereFramework framework;
    private AtmosphereConfig cfg;
    private ConcurrentHashMap<String, Object> props;

    @BeforeEach
    public void setUp() {
        processor = new AiEndpointProcessor();
        framework = mock(AtmosphereFramework.class);
        cfg = mock(AtmosphereConfig.class);
        props = new ConcurrentHashMap<>();
        when(cfg.properties()).thenReturn(props);
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
    }

    private void enableAppWide() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");
    }

    private void killSwitch() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("false");
    }

    private AiEndpointHandler handle(Class<?> endpointClass, Object instance) throws Exception {
        when(framework.newClassInstance(eq(Object.class), eq((Class) endpointClass)))
                .thenReturn(instance);
        processor.handle(framework, (Class) endpointClass);
        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(anyString(), handlerCaptor.capture(), any(List.class));
        return (AiEndpointHandler) handlerCaptor.getValue();
    }

    private HarnessPreset installedPreset() {
        var preset = props.get(HarnessPreset.PRESET_PROPERTY);
        assertInstanceOf(HarnessPreset.class, preset);
        return (HarnessPreset) preset;
    }

    // ---- conversation-memory gate ----

    @Test
    public void appWideTrueFlipsConversationMemoryOn() throws Exception {
        enableAppWide();

        var handler = handle(PlainEndpoint.class, new PlainEndpoint());

        assertNotNull(handler.memory(),
                "app-wide true + annotation conversationMemory=false must still resolve memory");
        assertEquals(20, handler.memory().maxMessages(),
                "maxHistoryMessages must come from the annotation default");
    }

    @Test
    public void annotationMemoryAlwaysWins() throws Exception {
        enableAppWide();

        var handler = handle(MemoryOnEndpoint.class, new MemoryOnEndpoint());

        assertNotNull(handler.memory());
        assertEquals(7, handler.memory().maxMessages(),
                "annotation-configured history budget must be preserved");
    }

    @Test
    public void bareEndpointStaysBareByDefault() throws Exception {
        var handler = handle(PlainEndpoint.class, new PlainEndpoint());

        assertNull(handler.memory(), "default-off endpoints must keep memory off");
        assertTrue(handler.interceptors().isEmpty(),
                "a bare endpoint must not gain the long-term-memory interceptor");
    }

    // ---- per-endpoint harness() opt-in ----

    @Test
    public void memoryHarnessOptsInWithoutTheAppWideFlag() throws Exception {
        var handler = handle(MemoryHarnessEndpoint.class, new MemoryHarnessEndpoint());

        assertNotNull(handler.memory(),
                "harness = {MEMORY} must resolve conversation memory without the app-wide flag");
        assertEquals(1, handler.interceptors().size());
        assertInstanceOf(LongTermMemoryInterceptor.class, handler.interceptors().get(0),
                "harness = {MEMORY} must attach the long-term-memory interceptor");
        assertNull(handler.cachePolicy(),
                "harness = {MEMORY} must not seed a prompt-cache default");
        assertEquals("ACTIVE",
                installedPreset().runtimeState()
                        .get(HarnessPreset.PRIMITIVE_CONVERSATION_MEMORY),
                "the annotation-driven attach must publish conversation memory as ACTIVE");
    }

    // ---- kill switch ----

    @Test
    public void killSwitchBeatsThePerEndpointAnnotation() throws Exception {
        killSwitch();

        var handler = handle(MemoryHarnessEndpoint.class, new MemoryHarnessEndpoint());

        assertNull(handler.memory(), "the kill switch must beat harness = {MEMORY}");
        assertTrue(handler.interceptors().isEmpty(),
                "the kill switch must suppress the long-term-memory attach");
        assertNull(handler.cachePolicy());
        assertEquals("INACTIVE(disabled)",
                installedPreset().runtimeState()
                        .get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY),
                "an unresolved primitive must stay INACTIVE(disabled)");
    }

    // ---- exclude-paths ----

    @Test
    public void excludedPathGetsNoHarnessPrimitives() throws Exception {
        enableAppWide();
        when(cfg.getInitParameter(HarnessPreset.EXCLUDE_PATHS_KEY))
                .thenReturn("/atmosphere/preset-plain");

        var handler = handle(PlainEndpoint.class, new PlainEndpoint());

        assertNull(handler.memory(), "excluded path must not gain conversation memory");
        assertTrue(handler.interceptors().isEmpty(),
                "excluded path must not gain the long-term-memory interceptor");
        assertNull(handler.cachePolicy(), "excluded path must not gain a cache default");
    }

    // ---- long-term-memory auto-attach ----

    @Test
    public void harnessAppendsLtmInterceptorAfterUserInterceptors() throws Exception {
        enableAppWide();
        when(framework.newClassInstance(eq(AiInterceptor.class), eq(PassThroughInterceptor.class)))
                .thenReturn(new PassThroughInterceptor());

        var handler = handle(UserInterceptorEndpoint.class, new UserInterceptorEndpoint());

        assertEquals(2, handler.interceptors().size());
        assertInstanceOf(PassThroughInterceptor.class, handler.interceptors().get(0),
                "user interceptors must keep FIFO precedence");
        assertInstanceOf(LongTermMemoryInterceptor.class, handler.interceptors().get(1),
                "the harness LTM interceptor must be appended AFTER user interceptors");

        var state = installedPreset().runtimeState();
        assertEquals("ACTIVE(" + InMemoryLongTermMemory.class.getName() + ")",
                state.get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY),
                "runtime-state must report the resolved store class");
    }

    @Test
    public void harnessSkipsLtmWhenUserAlreadyDeclaresOne() throws Exception {
        enableAppWide();
        var userLtm = new UserLtmInterceptor();
        when(framework.newClassInstance(eq(AiInterceptor.class), eq(UserLtmInterceptor.class)))
                .thenReturn(userLtm);

        var handler = handle(UserLtmEndpoint.class, new UserLtmEndpoint());

        var ltms = handler.interceptors().stream()
                .filter(i -> i instanceof LongTermMemoryInterceptor)
                .toList();
        assertEquals(1, ltms.size(), "the harness must not double-attach long-term memory");
        assertSame(userLtm, ltms.get(0), "the user's interceptor is authoritative");
    }

    // ---- prompt-cache default precedence ----

    @Test
    public void annotationCachePolicyWinsOverInitParamAndHarness() throws Exception {
        enableAppWide();
        when(cfg.getInitParameter(PromptCacheDefaults.DEFAULT_KEY)).thenReturn("conservative");

        var handler = handle(AggressiveCacheEndpoint.class, new AggressiveCacheEndpoint());

        assertEquals(CacheHint.CachePolicy.AGGRESSIVE, handler.cachePolicy());
    }

    @Test
    public void initParamBeatsHarnessDefault() throws Exception {
        enableAppWide();
        when(cfg.getInitParameter(PromptCacheDefaults.DEFAULT_KEY)).thenReturn("aggressive");

        var handler = handle(PlainEndpoint.class, new PlainEndpoint());

        assertEquals(CacheHint.CachePolicy.AGGRESSIVE, handler.cachePolicy());
    }

    @Test
    public void explicitNoneInitParamSuppressesHarnessDefault() throws Exception {
        enableAppWide();
        when(cfg.getInitParameter(PromptCacheDefaults.DEFAULT_KEY)).thenReturn("none");

        var handler = handle(PlainEndpoint.class, new PlainEndpoint());

        assertNull(handler.cachePolicy(),
                "an explicit 'none' must beat the harness conservative default");
    }

    @Test
    public void harnessSeedsConservativeWhenNothingElseConfigured() throws Exception {
        enableAppWide();

        var handler = handle(PlainEndpoint.class, new PlainEndpoint());

        assertEquals(CacheHint.CachePolicy.CONSERVATIVE, handler.cachePolicy());
    }

    @Test
    public void initParamWorksIndependentlyOfHarness() throws Exception {
        when(cfg.getInitParameter(PromptCacheDefaults.DEFAULT_KEY)).thenReturn("conservative");

        var handler = handle(PlainEndpoint.class, new PlainEndpoint());

        assertEquals(CacheHint.CachePolicy.CONSERVATIVE, handler.cachePolicy());
    }

    @Test
    public void noCachePolicyWithoutAnySource() throws Exception {
        var handler = handle(PlainEndpoint.class, new PlainEndpoint());

        assertNull(handler.cachePolicy());
    }

    // ---- compaction seam ----

    @Test
    public void compactionSeamSelectsLlmSummarizingForEndpointMemory() throws Exception {
        when(cfg.getInitParameter(CompactionConfig.STRATEGY_KEY)).thenReturn("summarizing");

        var handler = handle(MemoryOnEndpoint.class, new MemoryOnEndpoint());

        var memory = handler.memory();
        assertInstanceOf(InMemoryConversationMemory.class, memory);
        assertInstanceOf(LlmSummarizingCompaction.class,
                ((InMemoryConversationMemory) memory).compactionStrategy(),
                "org.atmosphere.ai.compaction=summarizing must select LlmSummarizingCompaction");
    }

    // ---- fixtures ----

    @AiEndpoint(path = "/atmosphere/preset-plain")
    public static class PlainEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/preset-memory-on",
            conversationMemory = true, maxHistoryMessages = 7)
    public static class MemoryOnEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/harness-memory", harness = {Harness.MEMORY})
    public static class MemoryHarnessEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/preset-aggressive-cache",
            promptCache = CacheHint.CachePolicy.AGGRESSIVE)
    public static class AggressiveCacheEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    public static class PassThroughInterceptor implements AiInterceptor {
    }

    @AiEndpoint(path = "/atmosphere/preset-user-interceptor",
            interceptors = {PassThroughInterceptor.class})
    public static class UserInterceptorEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    /** No-arg LTM subclass matching the instanceof skip rule. */
    public static class UserLtmInterceptor extends LongTermMemoryInterceptor {
        public UserLtmInterceptor() {
            super(new InMemoryLongTermMemory(5), MemoryExtractionStrategy.onSessionClose(),
                    AgentRuntimeResolver.resolve(), 5);
        }
    }

    @AiEndpoint(path = "/atmosphere/preset-user-ltm",
            interceptors = {UserLtmInterceptor.class})
    public static class UserLtmEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }
}
