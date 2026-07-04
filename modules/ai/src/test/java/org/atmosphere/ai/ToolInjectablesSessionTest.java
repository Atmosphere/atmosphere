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

import org.atmosphere.ai.plan.AgentPlanStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link ToolInjectablesSession} decorator (the Mode-Parity seam
 * that carries registration-time injectables onto the resource-free
 * {@code AiPipeline} paths) and the {@link AiPipeline#setToolInjectables}
 * round-trip: lazy merge, dispatch-time-wins conflict rule, and defensive
 * copying.
 */
public class ToolInjectablesSessionTest {

    /** Minimal delegate with a fixed injectables map. */
    private static StreamingSession delegateWith(Map<Class<?>, Object> injectables) {
        return new StreamingSession() {
            @Override
            public String sessionId() {
                return "s";
            }

            @Override
            public void send(String text) {
            }

            @Override
            public void sendMetadata(String key, Object value) {
            }

            @Override
            public void progress(String message) {
            }

            @Override
            public void complete() {
            }

            @Override
            public void complete(String summary) {
            }

            @Override
            public void error(Throwable t) {
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public Map<Class<?>, Object> injectables() {
                return injectables;
            }
        };
    }

    private static final AgentPlanStore STORE = new AgentPlanStore() {
        @Override
        public Optional<org.atmosphere.ai.plan.AgentPlan> get(String agentId,
                                                              String conversationId) {
            return Optional.empty();
        }

        @Override
        public void put(String agentId, String conversationId,
                        org.atmosphere.ai.plan.AgentPlan plan) {
        }
    };

    @Test
    public void mergesExtrasIntoTheDelegateScope() {
        var delegate = delegateWith(Map.of(String.class, "live"));
        var session = new ToolInjectablesSession(delegate, Map.of(AgentPlanStore.class, STORE));

        var merged = session.injectables();

        assertEquals("live", merged.get(String.class));
        assertSame(STORE, merged.get(AgentPlanStore.class));
    }

    @Test
    public void dispatchTimeEntriesWinOnConflict() {
        var dispatchTime = delegateWith(Map.of(String.class, "dispatch"));
        var session = new ToolInjectablesSession(dispatchTime,
                Map.of(String.class, "registration"));

        assertEquals("dispatch", session.injectables().get(String.class),
                "the delegate's dispatch-time entry must shadow the registration default");
    }

    @Test
    public void emptyExtrasPassThrough() {
        var base = Map.<Class<?>, Object>of(String.class, "live");
        var session = new ToolInjectablesSession(delegateWith(base), Map.of());

        assertSame(base, session.injectables(),
                "no extras must mean zero-copy passthrough");
    }

    @Test
    public void mergeIsLazySoLateDelegateEntriesAreSeen() {
        var mutable = new java.util.concurrent.ConcurrentHashMap<Class<?>, Object>();
        var session = new ToolInjectablesSession(delegateWith(mutable),
                Map.of(AgentPlanStore.class, STORE));

        assertTrue(session.injectables().containsKey(AgentPlanStore.class));
        mutable.put(String.class, "late");
        assertEquals("late", session.injectables().get(String.class),
                "entries added to the delegate after construction must be visible");
    }

    @Test
    public void pipelineSetterRoundTripsAndCopiesDefensively() {
        var pipeline = new AiPipeline(null, "", null, null, null,
                java.util.List.of(), java.util.List.of(), AiMetrics.NOOP);
        assertTrue(pipeline.toolInjectables().isEmpty(), "default must be empty");

        var source = new java.util.LinkedHashMap<Class<?>, Object>();
        source.put(AgentPlanStore.class, STORE);
        pipeline.setToolInjectables(source);
        source.put(String.class, "later");

        assertEquals(1, pipeline.toolInjectables().size(),
                "the setter must copy defensively");
        assertSame(STORE, pipeline.toolInjectables().get(AgentPlanStore.class));

        pipeline.setToolInjectables(null);
        assertTrue(pipeline.toolInjectables().isEmpty(), "null must clear");
    }
}
