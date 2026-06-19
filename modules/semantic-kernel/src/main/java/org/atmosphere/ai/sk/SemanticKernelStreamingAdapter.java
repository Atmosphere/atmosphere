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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.services.chatcompletion.StreamingChatContent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Bridges Microsoft Semantic Kernel's {@code Flux<StreamingChatContent<?>>}
 * stream to an Atmosphere {@link StreamingSession}. Phase 12 of the unified
 * {@code @Agent} API adds SK-Java as runtime #6.
 *
 * <p>For each streaming frame, calls {@code session.send(content.getContent())}
 * and watches the final {@code FunctionResultMetadata} for token usage so the
 * Phase 1 typed {@link StreamingSession#usage} event fires at the end of the
 * stream. Errors propagate to {@link StreamingSession#error}; normal
 * completion calls {@link StreamingSession#complete}.</p>
 */
final class SemanticKernelStreamingAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SemanticKernelStreamingAdapter.class);

    private SemanticKernelStreamingAdapter() {
    }

    /**
     * Subscribe to the SK flux <em>without</em> blocking the calling thread and
     * return the Reactor {@link reactor.core.Disposable} so the caller can abort
     * the in-flight stream via {@code dispose()} — disposing propagates an
     * upstream cancel to the Azure / OpenAI streaming call. Every content frame
     * is forwarded through the session exactly as the prior blocking path did.
     * {@code completion} is resolved on any terminal signal (complete, error,
     * cancel) so the caller's {@link org.atmosphere.ai.ExecutionHandle} can
     * settle {@code whenDone()}.
     *
     * <p>{@code fireModelStart} is fired by the runtime caller before this call
     * so {@code messageCount} + {@code toolCount} are visible to consumers from
     * the moment of dispatch; {@code fireModelEnd} fires on {@code doOnComplete}
     * (with the last captured token usage and wall-clock duration) and
     * {@code fireModelError} on {@code doOnError}.</p>
     */
    static reactor.core.Disposable drainCancellable(
            Flux<StreamingChatContent<?>> flux,
            StreamingSession session,
            java.util.List<org.atmosphere.ai.AgentLifecycleListener> listeners,
            String modelName,
            java.util.concurrent.CompletableFuture<Void> completion) {
        var startNanos = System.nanoTime();
        var lastUsage =
                new java.util.concurrent.atomic.AtomicReference<TokenUsage>();
        return flux.takeWhile(ignored -> !session.isClosed())
                .doOnNext(frame -> forwardFrame(frame, session, lastUsage))
                .doOnError(error -> {
                    org.atmosphere.ai.AgentLifecycleListener.fireModelError(
                            listeners, modelName, error);
                    logger.error("Semantic Kernel streaming error: {}", error.getMessage());
                    if (!session.isClosed()) {
                        session.error(error);
                    }
                    completion.complete(null);
                })
                .doOnComplete(() -> {
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    org.atmosphere.ai.AgentLifecycleListener.fireModelEnd(
                            listeners, modelName, lastUsage.get(), durationMs);
                    if (!session.isClosed()) {
                        session.complete();
                    }
                    completion.complete(null);
                })
                .doOnCancel(() -> completion.complete(null))
                .subscribe();
    }

    private static void forwardFrame(
            StreamingChatContent<?> frame,
            StreamingSession session,
            java.util.concurrent.atomic.AtomicReference<TokenUsage> lastUsage) {
        if (frame == null) {
            return;
        }
        // getContent() returns the text fragment for the current streaming chunk
        // (may be empty for metadata-only frames).
        var text = frame.getContent();
        if (text != null && !text.isEmpty()) {
            session.send(text);
        }
        // Phase 1 token usage extraction: the final streaming frame carries the
        // provider's token counts inside FunctionResultMetadata.getUsage(). The
        // usage object is provider-specific (CompletionsUsage for OpenAI); we
        // read it reflectively so this adapter does not hard-depend on Azure SDK
        // types — any provider whose usage object exposes
        // promptTokens/completionTokens/totalTokens is picked up.
        extractTokenUsage(frame, session, lastUsage);
    }

    private static void extractTokenUsage(
            StreamingChatContent<?> frame,
            StreamingSession session,
            java.util.concurrent.atomic.AtomicReference<TokenUsage> lastUsage) {
        try {
            var metadata = frame.getMetadata();
            if (metadata == null) {
                return;
            }
            Object usage = metadata.getUsage();
            if (usage == null) {
                return;
            }
            long input = readLong(usage, "getPromptTokens");
            long output = readLong(usage, "getCompletionTokens");
            long total = readLong(usage, "getTotalTokens");
            // SK reports a primitive 0 when the provider omits the total; treat
            // that as "not reported" so fromCounts falls back to input + output.
            var tokenUsage = TokenUsage.fromCounts(input, output, null, total > 0L ? total : null);
            if (tokenUsage.hasCounts()) {
                session.usage(tokenUsage);
                if (lastUsage != null) {
                    lastUsage.set(tokenUsage);
                }
            }
        } catch (Exception e) {
            // Usage extraction is best-effort — do not fail the stream if the
            // provider's usage object doesn't match the expected shape.
            logger.trace("SK usage extraction skipped: {}", e.getMessage());
        }
    }

    private static long readLong(Object target, String method) {
        try {
            var m = target.getClass().getMethod(method);
            var result = m.invoke(target);
            if (result instanceof Number number) {
                return number.longValue();
            }
        } catch (NoSuchMethodException ignored) {
            // Not every provider exposes every getter.
        } catch (ReflectiveOperationException e) {
            logger.trace("SK usage getter {} failed: {}", method, e.getMessage());
        }
        return 0L;
    }
}
