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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Wire-level regression harness for {@link ExecutionHandle#cancel()} across
 * the five AgentRuntime implementations that override the default cancel
 * (Built-in, Spring AI, LangChain4j, ADK, Koog). Semantic Kernel and Embabel
 * intentionally no-op cancel and are excluded from the matrix.
 *
 * <p>Protocol (one handler instance per runtime path; one resource per
 * client connection):</p>
 * <ul>
 *   <li>{@code slow} — start a long-running stream (~3s worth of tokens at
 *       100ms each) and return immediately. The in-flight handle is stashed
 *       on the resource so a subsequent {@code cancel} message can reach it.</li>
 *   <li>{@code cancel} — invoke {@link ExecutionHandle#cancel()} on the
 *       in-flight handle, await {@link ExecutionHandle#whenDone()}, and then
 *       call {@code session.complete()} so the client sees a single terminal
 *       {@code complete} frame. After this point the runtime must not emit
 *       any further {@code streaming-text} or {@code error} frames.</li>
 *   <li>{@code fast} — run a short stream to completion so the spec can
 *       verify the session is still usable after a prior cancel.</li>
 * </ul>
 *
 * <p>The {@code built-in} row instantiates a real
 * {@link BuiltInAgentRuntime} with a {@link SlowCancelAwareLlmClient} —
 * exercising the runtime's native stream-close-on-cancel path (regression
 * for commit {@code c29542f1e6} — {@code ExecutionHandle.Settable.cancel}
 * logs at TRACE).</p>
 *
 * <p>The remaining rows ({@code spring-ai}, {@code langchain4j}, {@code adk},
 * {@code koog}) share a wire-level path built on
 * {@link ExecutionHandle.Settable} with a virtual-thread-interrupting native
 * cancel — the same shape each real runtime uses (regression for commits
 * {@code cda1862619}, {@code 4ca8e983d8}, {@code 800cc7e73d},
 * {@code 7e2a24a986}). Per-runtime native primitives are covered by unit
 * tests in their respective modules; this wire test asserts the
 * handler/session/wire contract never regresses: terminal frame within
 * the deadline, no post-cancel frame leak, and session usability after
 * cancel.</p>
 */
public class CancelTestHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(CancelTestHandler.class);

    /** Resource-scoped state keyed by {@link AtmosphereResource#uuid()}. */
    private static final Map<String, InFlight> byResource = new ConcurrentHashMap<>();

    private final String runtimeLabel;

    public CancelTestHandler(String runtimeLabel) {
        this.runtimeLabel = runtimeLabel;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var message = reader.readLine();
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        var trimmed = message.trim();

        if ("cancel".equals(trimmed)) {
            handleCancel(resource);
            return;
        }

        // Start a new stream — slow (cancellable) or fast (completes quickly).
        var isSlow = "slow".equals(trimmed);
        Thread.ofVirtual().name("cancel-test-" + runtimeLabel).start(() ->
                startStream(resource, isSlow));
    }

    private void startStream(AtmosphereResource resource, boolean slow) {
        var session = StreamingSessions.start(resource);
        session.sendMetadata("runtime", runtimeLabel);
        session.sendMetadata("phase", slow ? "slow" : "fast");

        var texts = slow
                ? new String[] {
                        "Token1", " Token2", " Token3", " Token4", " Token5",
                        " Token6", " Token7", " Token8", " Token9", " Token10",
                        " Token11", " Token12", " Token13", " Token14", " Token15",
                        " Token16", " Token17", " Token18", " Token19", " Token20",
                        " Token21", " Token22", " Token23", " Token24", " Token25",
                        " Token26", " Token27", " Token28", " Token29", " Token30",
                }
                : new String[] { "ok", "-done" };
        var delayMs = slow ? 100L : 0L;
        var client = new SlowCancelAwareLlmClient("cancel-model-" + runtimeLabel,
                delayMs, texts);

        ExecutionHandle handle;
        if ("built-in".equals(runtimeLabel)) {
            // Real BuiltInAgentRuntime path: native cancel closes the
            // in-flight stream (which here is the SlowCancelAwareLlmClient's
            // stub Closeable). Exercises the runtime's virtual-thread + cancel
            // flag + stream-close wiring that shipped with the hard-cancel
            // patch.
            var runtime = new BuiltInAgentRuntime();
            runtime.configure(new AiConfig.LlmSettings(
                    client, "cancel-model-built-in", "local", ""));
            var context = new AgentExecutionContext(
                    slow ? "slow" : "fast",
                    null, "cancel-model-built-in", "cancel-agent",
                    session.sessionId(), "user-1", "conv-1",
                    List.of(), null, null, List.of(),
                    Map.of(), List.of(), null, null);
            handle = runtime.executeWithHandle(context, session);
        } else {
            // Wire-contract path for the framework runtimes that aren't on
            // the integration-tests classpath. Uses ExecutionHandle.Settable
            // — the exact helper every framework runtime wraps around its
            // native cancel primitive — and an interrupt-based native cancel
            // that matches Koog's Job.cancel() semantics (commit cda1862619).
            // Per-runtime native primitives are covered by unit tests in
            // modules/{spring-ai,langchain4j,adk,koog}/src/test.
            handle = runWithSettable(client, session);
        }

        var tracked = new InFlight(handle, session);
        byResource.put(resource.uuid(), tracked);
        logger.debug("Cancel test: started {} stream on {} (resource={})",
                slow ? "slow" : "fast", runtimeLabel, resource.uuid());

        // For fast streams we wait for completion so the terminal frame is
        // definitely emitted before onRequest returns for the next prompt.
        if (!slow) {
            try {
                handle.whenDone().get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best-effort: the session.complete() below is the wire-visible
                // terminal — any runtime error has already been reported via
                // session.error().
            }
            byResource.remove(resource.uuid(), tracked);
            if (!session.isClosed()) {
                session.complete();
            }
        }
    }

    private ExecutionHandle runWithSettable(SlowCancelAwareLlmClient client,
                                            StreamingSession session) {
        var threadRef = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var handle = new ExecutionHandle.Settable(() -> {
            // Native cancel: interrupt the streaming thread. The slow client
            // polls Thread.interrupted() / its cancel flag on every token
            // boundary and returns without calling session.complete() or
            // session.error(). This mirrors what Koog / ADK do with their
            // native Job / Runner cancel primitives.
            var t = threadRef.get();
            if (t != null) {
                t.interrupt();
            }
        });
        Thread.startVirtualThread(() -> {
            threadRef.set(Thread.currentThread());
            var cancelled = new AtomicBoolean();
            // Bridge the Settable's cancelled state into the LlmClient's
            // cooperative cancel flag so the slow loop exits at its next
            // token boundary even if the interrupt misses a blocked read.
            var watcher = Thread.ofVirtual().name("cancel-watcher").start(() -> {
                try {
                    handle.whenDone().join();
                } finally {
                    cancelled.set(true);
                }
            });
            try {
                client.streamChatCompletion(
                        ChatCompletionRequest.of(client.modelName(), "slow"),
                        session, cancelled, closeable -> { });
                handle.complete();
            } catch (Throwable t) {
                handle.completeExceptionally(t);
            } finally {
                watcher.interrupt();
            }
        });
        return handle;
    }

    private void handleCancel(AtmosphereResource resource) {
        var tracked = byResource.remove(resource.uuid());
        if (tracked == null) {
            logger.debug("Cancel test: no in-flight handle for resource {}", resource.uuid());
            return;
        }
        var t0 = System.nanoTime();
        tracked.handle.cancel();
        try {
            // Bounded await: the wire-level assertion is that the runtime
            // resolves the handle promptly. 1s is generous for a polling
            // loop with 100ms ticks.
            tracked.handle.whenDone().get(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warn("Cancel test: handle did not resolve within 1500ms on {}: {}",
                    runtimeLabel, e.toString());
        }
        var elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        if (!tracked.session.isClosed()) {
            tracked.session.sendMetadata("cancel.elapsedMs", elapsedMs);
            tracked.session.complete();
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
        byResource.clear();
    }

    /** State stashed per resource between the {@code slow} prompt and the {@code cancel} signal. */
    private record InFlight(ExecutionHandle handle, StreamingSession session) { }

    /**
     * Cancel-aware slow {@link LlmClient} used by every matrix row. Emits
     * tokens one-by-one with a delay and polls the {@code cancelled} flag
     * (plus {@link Thread#isInterrupted()}) at every token boundary. On
     * cancel it returns silently — no {@code session.complete()} or
     * {@code session.error()} — leaving the enclosing runtime to decide
     * how to terminate. The built-in runtime wrapper calls
     * {@link StreamingSession#send} directly and never touches
     * {@code session.error} on cancel, so no error frame escapes.
     */
    static final class SlowCancelAwareLlmClient implements LlmClient {
        private final String modelName;
        private final long delayPerTextMs;
        private final String[] texts;

        SlowCancelAwareLlmClient(String modelName, long delayPerTextMs, String... texts) {
            this.modelName = modelName;
            this.delayPerTextMs = delayPerTextMs;
            this.texts = texts;
        }

        String modelName() {
            return modelName;
        }

        @Override
        public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
            streamChatCompletion(request, session, new AtomicBoolean(), closeable -> { });
        }

        @Override
        public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session,
                                         AtomicBoolean cancelled,
                                         Consumer<Closeable> streamSink) {
            // Report a stub closeable so BuiltInAgentRuntime's cancel path
            // also exercises the stream-close branch (regression for the
            // c29542f1e6 TRACE logging + ownership fix).
            var closed = new AtomicBoolean();
            streamSink.accept(() -> closed.set(true));
            session.sendMetadata("model", modelName);
            for (String text : texts) {
                if (cancelled.get() || Thread.currentThread().isInterrupted() || closed.get()) {
                    return;
                }
                if (delayPerTextMs > 0) {
                    try {
                        Thread.sleep(delayPerTextMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (session.isClosed()) {
                    return;
                }
                session.send(text);
            }
            // Do not call session.complete() here — the enclosing handler is
            // responsible for emitting the terminal frame exactly once, so
            // that the spec can assert the terminal frame count regardless
            // of whether the stream finished naturally or via cancel.
        }
    }
}
