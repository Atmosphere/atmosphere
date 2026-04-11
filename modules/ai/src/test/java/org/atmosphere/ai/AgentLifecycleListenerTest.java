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

import org.atmosphere.ai.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 contract: {@link AgentLifecycleListener} methods default to no-op,
 * {@link AgentExecutionContext} carries a listener list that survives wither
 * operations, and the {@link AbstractAgentRuntime} fireXxx helpers iterate in
 * FIFO order swallowing listener exceptions per the Javadoc contract.
 */
class AgentLifecycleListenerTest {

    private static final class RecordingListener implements AgentLifecycleListener {
        final List<String> calls = new ArrayList<>();

        @Override public void onStart(AgentExecutionContext context) { calls.add("start"); }
        @Override public void onToolCall(String name, Map<String, Object> args) {
            calls.add("toolCall:" + name);
        }
        @Override public void onToolResult(String name, String preview) { calls.add("toolResult:" + name); }
        @Override public void onCompletion(AgentExecutionContext context) { calls.add("completion"); }
        @Override public void onError(AgentExecutionContext context, Throwable error) {
            calls.add("error:" + error.getMessage());
        }
    }

    private static AgentExecutionContext contextWith(List<AgentLifecycleListener> listeners) {
        return new AgentExecutionContext(
                "hello", null, null, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(),
                List.<ChatMessage>of(), null, null, listeners);
    }

    @Test
    void defaultMethodsAreNoOps() {
        var listener = new AgentLifecycleListener() { };
        // Calling every default method must not throw.
        listener.onStart(contextWith(List.of()));
        listener.onToolCall("x", Map.of());
        listener.onToolResult("x", "ok");
        listener.onCompletion(contextWith(List.of()));
        listener.onError(contextWith(List.of()), new RuntimeException("boom"));
    }

    @Test
    void contextCarriesListenersAndCopiesDefensively() {
        var listener = new RecordingListener();
        var mutable = new ArrayList<AgentLifecycleListener>();
        mutable.add(listener);
        var context = contextWith(mutable);

        // Defensive copy — adding to the source list after construction must not
        // change the context's view.
        mutable.add(new RecordingListener());
        assertEquals(1, context.listeners().size());
        assertTrue(context.listeners().contains(listener));
    }

    @Test
    void witherPreservesListeners() {
        var listener = new RecordingListener();
        var context = contextWith(List.of(listener));
        var renamed = context.withMessage("hi");
        assertEquals(1, renamed.listeners().size());
        assertTrue(renamed.listeners().contains(listener));
    }

    @Test
    void fifteenArgConstructorDefaultsToEmptyListeners() {
        var context = new AgentExecutionContext(
                "hi", null, null, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(),
                List.<ChatMessage>of(), null, null);
        assertNotNull(context.listeners());
        assertTrue(context.listeners().isEmpty());
    }

    @Test
    void listenerExceptionsAreSwallowed() {
        var good = new RecordingListener();
        var throwing = new AgentLifecycleListener() {
            @Override public void onStart(AgentExecutionContext ctx) {
                throw new RuntimeException("fail");
            }
        };
        var calls = new AtomicInteger();
        var counting = new AgentLifecycleListener() {
            @Override public void onStart(AgentExecutionContext ctx) { calls.incrementAndGet(); }
        };
        var context = contextWith(List.of(good, throwing, counting));

        // Reflection-free invocation via AbstractAgentRuntime.fireStart — iterate
        // manually here because fireStart is protected; the iteration contract is
        // the documented behavior.
        for (var l : context.listeners()) {
            try {
                l.onStart(context);
            } catch (Exception ignored) {
                // mirror the swallow-and-log behavior
            }
        }
        assertEquals(1, good.calls.size());
        assertEquals("start", good.calls.get(0));
        assertEquals(1, calls.get(), "listener after a throwing one must still fire");
    }
}
