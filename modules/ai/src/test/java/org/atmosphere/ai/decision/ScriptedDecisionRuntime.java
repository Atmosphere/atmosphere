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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test {@link AgentRuntime} whose reply to each dispatched question is scripted.
 * By default it behaves like the Built-in runtime: {@code executeWithHandle}
 * returns a cancellable handle at once and the reply is produced on another
 * virtual thread. {@link #synchronous()} makes it block the dispatching thread
 * instead, like a runtime whose {@code execute} only returns when done and that
 * publishes no handle.
 */
public final class ScriptedDecisionRuntime implements AgentRuntime {

    /** Produces the reply for one dispatch. */
    @FunctionalInterface
    public interface Script {
        void reply(AgentExecutionContext context, StreamingSession session) throws Exception;
    }

    private final Script script;
    private final Set<AiCapability> capabilities;
    private final boolean synchronous;
    private final ConcurrentLinkedQueue<AgentExecutionContext> contexts = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final CountDownLatch cancelled = new CountDownLatch(1);

    public ScriptedDecisionRuntime(Script script) {
        this(script, Set.of(AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT,
                AiCapability.NATIVE_STRUCTURED_OUTPUT), false);
    }

    private ScriptedDecisionRuntime(Script script, Set<AiCapability> capabilities, boolean synchronous) {
        this.script = script;
        this.capabilities = capabilities;
        this.synchronous = synchronous;
    }

    /** Same script, without {@link AiCapability#NATIVE_STRUCTURED_OUTPUT}. */
    public ScriptedDecisionRuntime withoutNativeSchema() {
        return new ScriptedDecisionRuntime(script,
                Set.of(AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT), synchronous);
    }

    /** Same script, run on the dispatching thread with no handle published. */
    public ScriptedDecisionRuntime synchronous() {
        return new ScriptedDecisionRuntime(script, capabilities, true);
    }

    /** Emit {@code json} as the reply text and complete. */
    public static void reply(StreamingSession session, String json) {
        session.send(json);
        session.complete();
    }

    public List<AgentExecutionContext> contexts() {
        return List.copyOf(contexts);
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    /** Released once any handle this runtime returned is cancelled. */
    public CountDownLatch cancelled() {
        return cancelled;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // Nothing to configure.
    }

    @Override
    public Set<AiCapability> capabilities() {
        return capabilities;
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        run(context, session);
    }

    @Override
    public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
        if (synchronous) {
            run(context, session);
            return ExecutionHandle.completed();
        }
        var handle = new ExecutionHandle.Settable(cancelled::countDown);
        Thread.ofVirtual().start(() -> {
            run(context, session);
            handle.complete();
        });
        return handle;
    }

    private void run(AgentExecutionContext context, StreamingSession session) {
        contexts.add(context);
        var now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        var wrapper = new InFlightReleasingSession(session);
        try {
            script.reply(context, wrapper);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            wrapper.error(e);
        } catch (Exception e) {
            wrapper.error(e);
        } finally {
            wrapper.release();
        }
    }

    /**
     * Decrements the in-flight count before forwarding the terminal event, so
     * a permit freed by the terminal event can never make the observed peak
     * exceed the real one.
     */
    private final class InFlightReleasingSession implements StreamingSession {
        private final StreamingSession delegate;
        private final java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();

        InFlightReleasingSession(StreamingSession delegate) {
            this.delegate = delegate;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
            }
        }

        @Override public String sessionId() { return delegate.sessionId(); }
        @Override public void send(String text) { delegate.send(text); }
        @Override public void sendMetadata(String key, Object value) { delegate.sendMetadata(key, value); }
        @Override public void progress(String message) { delegate.progress(message); }
        @Override public void usage(org.atmosphere.ai.TokenUsage usage) { delegate.usage(usage); }
        @Override public void confidence(org.atmosphere.ai.AiConfidence confidence) {
            delegate.confidence(confidence);
        }
        @Override public void complete() {
            release();
            delegate.complete();
        }
        @Override public void complete(String summary) {
            release();
            delegate.complete(summary);
        }
        @Override public void error(Throwable t) {
            release();
            delegate.error(t);
        }
        @Override public boolean isClosed() { return delegate.isClosed(); }
    }
}
