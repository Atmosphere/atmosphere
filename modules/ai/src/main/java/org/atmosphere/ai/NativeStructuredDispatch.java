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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-invocation seam that turns on provider-native structured-output
 * enforcement and, under {@link NativeStructuredOutputMode#AUTO}, supplies the
 * graceful fall-back to the prompt-injection path when a provider rejects the
 * schema.
 *
 * <p>This sits where {@link StructuredOutputRetry} sits — between the pipeline
 * and {@code runtime.executeWithHandle(...)} — but, unlike the reprompt loop, it
 * does <strong>not</strong> buffer the happy path: the native attempt streams
 * live straight through {@code finalTarget}, so progressive structured-field
 * events are unaffected. Only a <em>pre-stream</em> schema rejection (the only
 * point at which a provider can refuse a schema) triggers a re-dispatch, and
 * because nothing has been forwarded downstream yet the same {@code finalTarget}
 * is reused cleanly. If the provider rejects the schema <em>after</em> output has
 * begun (vanishingly rare), the error is forwarded as-is rather than risking a
 * double-stream — the {@link FallbackGuardSession} tracks that boundary.</p>
 */
final class NativeStructuredDispatch {

    private static final Logger logger = LoggerFactory.getLogger(NativeStructuredDispatch.class);

    private NativeStructuredDispatch() {
    }

    /**
     * Dispatch {@code context} through {@code runtime} with native structured
     * output applied per {@code mode}. The caller has already established that the
     * request declares a response type and the runtime advertises
     * {@link AiCapability#NATIVE_STRUCTURED_OUTPUT}.
     */
    static ExecutionHandle executeWithHandle(AgentRuntime runtime,
                                             AgentExecutionContext context,
                                             StreamingSession finalTarget,
                                             NativeStructuredOutputMode mode) {
        var schema = NativeStructuredOutput.schemaFor(context.responseType());
        if (schema == null || mode == NativeStructuredOutputMode.DISABLED) {
            // No machine-readable schema (or disabled) → plain dispatch; the
            // pipeline's prompt-injection path still carries the schema.
            return runtime.executeWithHandle(context, finalTarget);
        }

        var nativeContext = NativeStructuredOutput.withApply(context, schema);
        if (mode == NativeStructuredOutputMode.ENABLED) {
            // Fail-fast: apply native, let any rejection propagate.
            return runtime.executeWithHandle(nativeContext, finalTarget);
        }

        // AUTO: apply native, guard for a pre-stream schema rejection.
        var done = new CompletableFuture<Void>();
        var currentHandle = new AtomicReference<ExecutionHandle>();

        Runnable fallback = () -> {
            logger.info("Native structured output rejected by provider; falling back to "
                    + "prompt-injection path for session {}", finalTarget.sessionId());
            var plainContext = NativeStructuredOutput.withoutApply(context);
            var h2 = runtime.executeWithHandle(plainContext, finalTarget);
            currentHandle.set(h2);
            h2.whenDone().whenComplete((v, t) -> done.complete(null));
        };

        var guard = new FallbackGuardSession(finalTarget, fallback);
        var h1 = runtime.executeWithHandle(nativeContext, guard);
        currentHandle.set(h1);
        h1.whenDone().whenComplete((v, t) -> {
            // When the guard already triggered the fall-back, the re-dispatch
            // owns completion of `done`; otherwise the native attempt is terminal.
            if (!guard.fellBack()) {
                done.complete(null);
            }
        });

        return new ExecutionHandle() {
            @Override
            public void cancel() {
                var h = currentHandle.get();
                if (h != null) {
                    h.cancel();
                }
            }

            @Override
            public boolean isDone() {
                return done.isDone();
            }

            @Override
            public CompletableFuture<Void> whenDone() {
                return done;
            }
        };
    }

    /**
     * Transparent decorator over the real target that intercepts exactly one
     * thing: a terminal {@code error} that (a) arrives before any output has been
     * forwarded downstream and (b) looks like a provider schema rejection. In that
     * case it swallows the error and runs the fall-back re-dispatch instead of
     * propagating; every other call — including a schema rejection that somehow
     * arrives mid-stream — is forwarded verbatim so behavior is otherwise
     * byte-identical to dispatching straight to the target.
     */
    private static final class FallbackGuardSession implements StreamingSession {

        private final StreamingSession delegate;
        private final Runnable fallback;
        private volatile boolean forwarded;
        private final java.util.concurrent.atomic.AtomicBoolean triggered =
                new java.util.concurrent.atomic.AtomicBoolean();

        FallbackGuardSession(StreamingSession delegate, Runnable fallback) {
            this.delegate = delegate;
            this.fallback = fallback;
        }

        boolean fellBack() {
            return triggered.get();
        }

        @Override
        public void error(Throwable t) {
            if (!forwarded && NativeStructuredOutput.isSchemaRejection(t)
                    && triggered.compareAndSet(false, true)) {
                fallback.run();
                return;
            }
            delegate.error(t);
        }

        // -- everything below forwards verbatim; output-bearing calls flip the
        //    `forwarded` latch so a post-output rejection cannot re-dispatch. --

        @Override
        public String sessionId() {
            return delegate.sessionId();
        }

        @Override
        public java.util.Optional<String> runId() {
            return delegate.runId();
        }

        @Override
        public java.util.Map<Class<?>, Object> injectables() {
            return delegate.injectables();
        }

        @Override
        public void send(String text) {
            forwarded = true;
            delegate.send(text);
        }

        @Override
        public void sendContent(Content content) {
            forwarded = true;
            delegate.sendContent(content);
        }

        @Override
        public void emit(AiEvent event) {
            forwarded = true;
            delegate.emit(event);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            forwarded = true;
            delegate.sendMetadata(key, value);
        }

        @Override
        public void usage(TokenUsage usage) {
            forwarded = true;
            delegate.usage(usage);
        }

        @Override
        public void confidence(AiConfidence confidence) {
            forwarded = true;
            delegate.confidence(confidence);
        }

        @Override
        public void toolCallDelta(String toolCallId, String argsChunk) {
            forwarded = true;
            delegate.toolCallDelta(toolCallId, argsChunk);
        }

        @Override
        public void progress(String message) {
            forwarded = true;
            delegate.progress(message);
        }

        @Override
        public void complete() {
            forwarded = true;
            delegate.complete();
        }

        @Override
        public void complete(String summary) {
            forwarded = true;
            delegate.complete(summary);
        }

        @Override
        public void handoff(String agentName, String message) {
            forwarded = true;
            delegate.handoff(agentName, message);
        }

        @Override
        public void stream(String message) {
            forwarded = true;
            delegate.stream(message);
        }

        @Override
        public void onTerminate(AutoCloseable resource) {
            forwarded = true;
            delegate.onTerminate(resource);
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public boolean hasErrored() {
            return delegate.hasErrored();
        }
    }
}
