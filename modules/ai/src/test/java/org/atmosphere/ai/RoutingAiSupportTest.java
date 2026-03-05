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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RoutingAiSupportTest {

    @Test
    public void testRoutesToSelectedBackend() {
        var primary = new StubAiSupport("primary");
        var secondary = new StubAiSupport("secondary");
        var router = new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER);
        var routing = new RoutingAiSupport(router, List.of(primary, secondary));

        var session = mock(StreamingSession.class);
        routing.stream(new AiRequest("Hello"), session);

        assertTrue(primary.called);
        assertFalse(secondary.called);
    }

    @Test
    public void testFailoverOnPrimaryFailure() {
        var primary = new StubAiSupport("primary");
        primary.shouldFail = true;
        var secondary = new StubAiSupport("secondary");
        var router = new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER);
        var routing = new RoutingAiSupport(router, List.of(primary, secondary));

        var session = mock(StreamingSession.class);
        routing.stream(new AiRequest("Hello"), session);

        assertTrue(primary.called);
        assertTrue(secondary.called);
    }

    @Test
    public void testErrorWhenNoBackendsAvailable() {
        var router = new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER);
        var routing = new RoutingAiSupport(router, List.of());

        var session = mock(StreamingSession.class);
        routing.stream(new AiRequest("Hello"), session);

        verify(session).error(any(IllegalStateException.class));
    }

    @Test
    public void testCapabilitiesAggregated() {
        var primary = new StubAiSupport("primary");
        primary.caps = Set.of(AiCapability.TEXT_STREAMING);
        var secondary = new StubAiSupport("secondary");
        secondary.caps = Set.of(AiCapability.TOOL_CALLING);
        var router = new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER);
        var routing = new RoutingAiSupport(router, List.of(primary, secondary));

        var caps = routing.capabilities();
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING));
        assertTrue(caps.contains(AiCapability.TOOL_CALLING));
    }

    @Test
    public void testNameIncludesBackends() {
        var primary = new StubAiSupport("primary");
        var secondary = new StubAiSupport("secondary");
        var router = new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER);
        var routing = new RoutingAiSupport(router, List.of(primary, secondary));

        assertTrue(routing.name().contains("primary"));
        assertTrue(routing.name().contains("secondary"));
    }

    static class StubAiSupport implements AiSupport {
        final String id;
        boolean called;
        boolean shouldFail;
        Set<AiCapability> caps = Set.of(AiCapability.TEXT_STREAMING);

        StubAiSupport(String id) {
            this.id = id;
        }

        @Override public String name() { return id; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void stream(AiRequest request, StreamingSession session) {
            called = true;
            if (shouldFail) {
                throw new RuntimeException("Simulated failure");
            }
        }

        @Override
        public Set<AiCapability> capabilities() {
            return caps;
        }
    }
}
