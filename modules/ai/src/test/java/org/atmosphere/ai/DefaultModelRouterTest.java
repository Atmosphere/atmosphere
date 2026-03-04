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

import org.atmosphere.ai.ModelRouter.FallbackStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultModelRouter}.
 */
public class DefaultModelRouterTest {

    @Test
    public void testFailoverReturnsFirstHealthy() {
        var router = new DefaultModelRouter(FallbackStrategy.FAILOVER);
        var backends = List.of(backend("gpt4", AiCapability.TEXT_STREAMING),
                backend("claude", AiCapability.TEXT_STREAMING));
        var request = new AiRequest("hello");

        var result = router.route(request, backends, Set.of(AiCapability.TEXT_STREAMING));
        assertTrue(result.isPresent());
        assertEquals("gpt4", result.get().name());
    }

    @Test
    public void testFailoverSkipsUnhealthyBackend() {
        var router = new DefaultModelRouter(FallbackStrategy.FAILOVER, 2, Duration.ofMinutes(5));
        var gpt4 = backend("gpt4", AiCapability.TEXT_STREAMING);
        var claude = backend("claude", AiCapability.TEXT_STREAMING);
        var backends = List.of(gpt4, claude);

        // Mark gpt4 as unhealthy
        router.reportFailure(gpt4, new RuntimeException("timeout"));
        router.reportFailure(gpt4, new RuntimeException("timeout"));

        var result = router.route(new AiRequest("hello"), backends, Set.of(AiCapability.TEXT_STREAMING));
        assertTrue(result.isPresent());
        assertEquals("claude", result.get().name());
    }

    @Test
    public void testSuccessResetsHealthCounter() {
        var router = new DefaultModelRouter(FallbackStrategy.FAILOVER, 2, Duration.ofMinutes(5));
        var gpt4 = backend("gpt4", AiCapability.TEXT_STREAMING);
        var backends = List.of(gpt4);

        router.reportFailure(gpt4, new RuntimeException("timeout"));
        router.reportSuccess(gpt4);
        router.reportFailure(gpt4, new RuntimeException("timeout"));

        // Only 1 consecutive failure — should still be healthy
        var result = router.route(new AiRequest("hello"), backends, Set.of(AiCapability.TEXT_STREAMING));
        assertTrue(result.isPresent());
    }

    @Test
    public void testRoundRobinDistributes() {
        var router = new DefaultModelRouter(FallbackStrategy.ROUND_ROBIN);
        var backends = List.of(
                backend("a", AiCapability.TEXT_STREAMING),
                backend("b", AiCapability.TEXT_STREAMING),
                backend("c", AiCapability.TEXT_STREAMING));

        var request = new AiRequest("hello");
        var first = router.route(request, backends, Set.of(AiCapability.TEXT_STREAMING));
        var second = router.route(request, backends, Set.of(AiCapability.TEXT_STREAMING));
        var third = router.route(request, backends, Set.of(AiCapability.TEXT_STREAMING));

        // Should cycle through backends
        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertTrue(third.isPresent());
        assertNotEquals(first.get().name(), second.get().name());
    }

    @Test
    public void testCapabilityFiltering() {
        var router = new DefaultModelRouter(FallbackStrategy.FAILOVER);
        var textOnly = backend("text-only", AiCapability.TEXT_STREAMING);
        var withTools = backend("with-tools", AiCapability.TEXT_STREAMING, AiCapability.TOOL_CALLING);

        var result = router.route(new AiRequest("hello"),
                List.of(textOnly, withTools),
                Set.of(AiCapability.TEXT_STREAMING, AiCapability.TOOL_CALLING));

        assertTrue(result.isPresent());
        assertEquals("with-tools", result.get().name());
    }

    @Test
    public void testEmptyBackendsReturnsEmpty() {
        var router = new DefaultModelRouter();
        var result = router.route(new AiRequest("hello"), List.of(), Set.of());
        assertTrue(result.isEmpty());
    }

    @Test
    public void testNoMatchingCapabilitiesReturnsEmpty() {
        var router = new DefaultModelRouter();
        var backend = backend("text-only", AiCapability.TEXT_STREAMING);

        var result = router.route(new AiRequest("hello"),
                List.of(backend),
                Set.of(AiCapability.VISION));

        assertTrue(result.isEmpty());
    }

    @Test
    public void testContentBasedRoutingByModelHint() {
        var router = new DefaultModelRouter(FallbackStrategy.CONTENT_BASED);
        var gpt4 = backend("openai-gpt4", AiCapability.TEXT_STREAMING);
        var claude = backend("anthropic-claude", AiCapability.TEXT_STREAMING);

        var request = new AiRequest("hello", null, "claude", Map.of(), List.of());
        var result = router.route(request, List.of(gpt4, claude), Set.of(AiCapability.TEXT_STREAMING));

        assertTrue(result.isPresent());
        assertEquals("anthropic-claude", result.get().name());
    }

    @Test
    public void testAllUnhealthyFallsBackToAllBackends() {
        var router = new DefaultModelRouter(FallbackStrategy.FAILOVER, 1, Duration.ofMinutes(5));
        var gpt4 = backend("gpt4", AiCapability.TEXT_STREAMING);

        router.reportFailure(gpt4, new RuntimeException("timeout"));

        // All healthy backends exhausted — falls back to all available
        var result = router.route(new AiRequest("hello"),
                List.of(gpt4), Set.of(AiCapability.TEXT_STREAMING));
        assertTrue(result.isPresent());
        assertEquals("gpt4", result.get().name());
    }

    private static AiSupport backend(String name, AiCapability... capabilities) {
        return new AiSupport() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public int priority() {
                return 100;
            }

            @Override
            public void configure(AiConfig.LlmSettings settings) {
            }

            @Override
            public void stream(AiRequest request, StreamingSession session) {
            }

            @Override
            public Set<AiCapability> capabilities() {
                return Set.of(capabilities);
            }
        };
    }
}
