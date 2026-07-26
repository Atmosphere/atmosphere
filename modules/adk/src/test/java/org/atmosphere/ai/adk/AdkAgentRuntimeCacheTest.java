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
package org.atmosphere.ai.adk;

import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.CacheHint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code PROMPT_CACHING} wiring on the ADK runtime (Correctness
 * Invariant #5 — Runtime Truth). ADK's {@link ContextCacheConfig} is
 * {@code App.Builder}-scoped and cannot be swapped on an already-built
 * {@link Runner}, so a per-request {@link CacheHint} has to do two things for
 * the capability to be honest, and both are asserted here:
 *
 * <ol>
 *   <li><b>Routing</b> — an enabled hint must force a fresh per-request
 *       {@code Runner}. Without that branch the hint lands on a shared runner
 *       that was built without any cache config and is silently dropped.</li>
 *   <li><b>Translation</b> — the hint's TTL must reach the App's
 *       {@link ContextCacheConfig}, with ADK's {@code maxInvocations=0}
 *       ("no limit") and the runtime's conservative {@code minTokens} floor.</li>
 * </ol>
 *
 * <p><b>On the reflection.</b> ADK 0.9 exposes {@code App.contextCacheConfig()}
 * publicly but gives {@link Runner} no {@code app()} or
 * {@code contextCacheConfig()} accessor, while
 * {@code AdkAgentRuntime.buildRequestRunner} legitimately returns a
 * {@code Runner}. Reading the {@code Runner.contextCacheConfig} field the
 * builder copies over is therefore the strongest honest assertion available
 * without changing production code to widen a seam purely for a test. The
 * lookup fails loudly if a future ADK release renames the field, rather than
 * degrading into a vacuous pass.</p>
 */
class AdkAgentRuntimeCacheTest {

    @BeforeEach
    void configureSettings() {
        // buildRequestRunner's default path constructs a Gemini client from the
        // configured settings; the key is never dialed in these tests.
        AiConfig.configure("remote", "gemini-2.5-flash", "test-key", null);
    }

    @Test
    void cacheHintForcesAPerRequestRunner() {
        var built = new AtomicBoolean();
        var runtime = new AdkAgentRuntime() {
            @Override
            Runner buildRequestRunner(AgentExecutionContext context, StreamingSession session) {
                built.set(true);
                return stubRunner();
            }
        };

        var handle = runtime.doExecuteWithHandle(
                stubRunner(), contextWithCacheHint(Duration.ofMinutes(11)), noopSession());

        assertTrue(built.get(),
                "an enabled CacheHint must force a per-request Runner — ADK's "
                        + "ContextCacheConfig is App.Builder-scoped, so a hint applied to the "
                        + "shared runner would be silently dropped");
        handle.cancel();
    }

    @Test
    void noCacheHintKeepsTheSharedRunner() {
        var runtime = new AdkAgentRuntime() {
            @Override
            Runner buildRequestRunner(AgentExecutionContext context, StreamingSession session) {
                throw new AssertionError(
                        "a tool-less, hint-less context must not build a per-request Runner");
            }
        };

        var handle = runtime.doExecuteWithHandle(stubRunner(), plainContext(), noopSession());
        handle.cancel();
    }

    @Test
    void cacheHintTtlReachesAdkContextCacheConfig() {
        var runtime = new AdkAgentRuntime();

        var runner = runtime.buildRequestRunner(
                contextWithCacheHint(Duration.ofMinutes(11)), noopSession());

        var config = contextCacheConfigOf(runner);
        assertNotNull(config,
                "an enabled CacheHint must attach a ContextCacheConfig to the per-request App");
        assertEquals(Duration.ofMinutes(11), config.ttl(),
                "the hint's TTL must reach ADK's cached-content expiry");
        assertEquals(0, config.maxInvocations(),
                "ADK convention: maxInvocations=0 means no limit");
        assertTrue(config.minTokens() > 0,
                "a positive minTokens floor must be set so Gemini only caches "
                        + "prompt blocks worth caching: " + config.minTokens());
    }

    @Test
    void cacheHintWithoutExplicitTtlFallsBackToTheRuntimeDefault() {
        var runtime = new AdkAgentRuntime();
        var hint = CacheHint.conservative("adk-cache-test");
        assertTrue(hint.enabled(), "a CONSERVATIVE hint must read as enabled");
        assertTrue(hint.ttl().isEmpty(), "this fixture must not carry an explicit TTL");

        var runner = runtime.buildRequestRunner(contextWith(hint), noopSession());

        var config = contextCacheConfigOf(runner);
        assertNotNull(config,
                "a TTL-less but enabled hint must still attach a ContextCacheConfig");
        assertEquals(Duration.ofMinutes(5), config.ttl(),
                "a hint without an explicit TTL must fall back to the runtime's 5-minute default");
    }

    @Test
    void plainContextAttachesNoContextCacheConfig() {
        var runtime = new AdkAgentRuntime();

        var runner = runtime.buildRequestRunner(plainContext(), noopSession());

        assertNull(contextCacheConfigOf(runner),
                "a context with no CacheHint must leave the per-request App's cache config "
                        + "unset — otherwise every request would pay for cached content");
    }

    @Test
    void disabledCacheHintAttachesNoContextCacheConfig() {
        var runtime = new AdkAgentRuntime();
        var none = CacheHint.none();
        assertFalse(none.enabled(), "CacheHint.none() must read as disabled");

        var runner = runtime.buildRequestRunner(contextWith(none), noopSession());

        assertNull(contextCacheConfigOf(runner),
                "an explicitly disabled CacheHint must not turn caching on — the metadata "
                        + "slot being present is not the same as the caller opting in");
    }

    // -- helpers --

    /**
     * Read the {@link ContextCacheConfig} the {@code Runner.Builder} copied off
     * the App. See the class Javadoc for why this is reflective; a rename in a
     * future ADK release fails here loudly.
     */
    private static ContextCacheConfig contextCacheConfigOf(Runner runner) {
        try {
            var field = Runner.class.getDeclaredField("contextCacheConfig");
            field.setAccessible(true);
            return (ContextCacheConfig) field.get(runner);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "com.google.adk.runner.Runner no longer carries a 'contextCacheConfig' "
                            + "field; re-point this assertion at whatever accessor the new ADK "
                            + "release exposes (App.contextCacheConfig() is public)", e);
        }
    }

    private static AgentExecutionContext contextWithCacheHint(Duration ttl) {
        return contextWith(new CacheHint(
                CacheHint.CachePolicy.CONSERVATIVE,
                Optional.of("adk-cache-test"),
                Optional.of(ttl)));
    }

    private static AgentExecutionContext contextWith(CacheHint hint) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(),
                Map.of(CacheHint.METADATA_KEY, hint),
                List.of(), null, null);
    }

    private static AgentExecutionContext plainContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    private static StreamingSession noopSession() {
        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);
        return session;
    }

    private static Runner stubRunner() {
        var runner = mock(Runner.class);
        var sessionService = mock(BaseSessionService.class);
        var session = mock(Session.class);
        when(runner.sessionService()).thenReturn(sessionService);
        when(runner.appName()).thenReturn("cache-test-app");
        when(sessionService.getSession(anyString(), anyString(), anyString(), any()))
                .thenReturn(Maybe.just(session));
        when(runner.runAsync(anyString(), anyString(), any(Content.class)))
                .thenReturn(Flowable.<Event>never());
        when(runner.close()).thenReturn(Completable.complete());
        return runner;
    }
}
