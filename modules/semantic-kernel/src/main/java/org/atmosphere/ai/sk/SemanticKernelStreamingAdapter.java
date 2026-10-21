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

import com.microsoft.semantickernel.contents.FunctionCallContent;
import com.microsoft.semantickernel.orchestration.FunctionResultMetadata;
import com.microsoft.semantickernel.services.chatcompletion.AuthorRole;
import com.microsoft.semantickernel.services.chatcompletion.ChatMessageContent;
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
            org.atmosphere.ai.ModelCallScope modelScope,
            java.util.concurrent.CompletableFuture<Void> completion) {
        var lastUsage =
                new java.util.concurrent.atomic.AtomicReference<TokenUsage>();
        return flux.takeWhile(ignored -> !session.isClosed())
                .doOnNext(frame -> forwardFrame(frame, session, lastUsage))
                .doOnError(error -> {
                    modelScope.fail(error);
                    logger.error("Semantic Kernel streaming error: {}", error.getMessage());
                    if (!session.isClosed()) {
                        // Classify the raw SK/provider exception into the shared
                        // AiProviderException taxonomy so retry, metrics, and
                        // routing see a typed failure; no-signal errors pass
                        // through unchanged.
                        session.error(org.atmosphere.ai.ProviderErrorClassifier.wrap(error));
                    }
                    completion.complete(null);
                })
                .doOnComplete(() -> {
                    modelScope.complete(lastUsage.get());
                    if (!session.isClosed()) {
                        session.complete();
                    }
                    completion.complete(null);
                })
                .doOnCancel(() -> completion.complete(null))
                .subscribe();
    }

    /**
     * Subscribe to the non-streaming chat call of a turn whose tools Semantic
     * Kernel invokes itself, and return the Reactor {@link reactor.core.Disposable}
     * so the caller can abort it. SK 1.5.0 runs its auto-invoke loop only on the
     * non-streaming call ({@code getStreamingChatMessageContentsAsync} refuses
     * auto-invoke, and its frames carry no tool calls), so the answer reaches
     * the session as one {@code send} once the loop ends.
     *
     * <p>The answer is the last assistant message; its token usage (the last
     * round's) goes to {@link StreamingSession#usage}. A last assistant message
     * that still asks for tools means SK's loop ran out of auto-invoke attempts:
     * the session ends with an error rather than an empty answer. A
     * {@link SemanticKernelToolBridge.TurnEndedException} ends the dispatch
     * without an error: the tool round already ended the turn. Every terminal
     * signal resolves {@code completion} (Correctness Invariant #2).</p>
     */
    static reactor.core.Disposable drainToolTurn(
            reactor.core.publisher.Mono<java.util.List<ChatMessageContent<?>>> call,
            StreamingSession session,
            org.atmosphere.ai.ModelCallScope modelScope,
            java.util.concurrent.CompletableFuture<Void> completion) {
        var lastUsage = new java.util.concurrent.atomic.AtomicReference<TokenUsage>();
        return call
                .doOnCancel(() -> completion.complete(null))
                .subscribe(
                        messages -> {
                            var answer = lastAssistantMessage(messages);
                            if (answer != null) {
                                extractTokenUsage(answer.getMetadata(), session, lastUsage);
                            }
                            if (answer != null && (answer.getContent() == null || answer.getContent().isBlank())
                                    && asksForTools(answer)) {
                                var exhausted = new IllegalStateException(
                                        "Semantic Kernel's tool loop ran out of auto-invoke attempts"
                                                + " with tool calls still pending");
                                modelScope.fail(exhausted);
                                if (!session.isClosed()) {
                                    session.error(exhausted);
                                }
                                completion.complete(null);
                                return;
                            }
                            var text = answer != null ? answer.getContent() : null;
                            if (text != null && !text.isEmpty() && !session.isClosed()) {
                                session.send(text);
                            }
                            modelScope.complete(lastUsage.get());
                            if (!session.isClosed()) {
                                session.complete();
                            }
                            completion.complete(null);
                        },
                        error -> {
                            if (SemanticKernelToolBridge.TurnEndedException.endedTheTurn(error)) {
                                // A tool round ended the turn and the hook stopped
                                // SK's loop before its follow-up model call: the
                                // turn already carries its own end.
                                logger.debug("Semantic Kernel tool loop stopped: {}", error.getMessage());
                                modelScope.complete(lastUsage.get());
                                if (!session.isClosed()) {
                                    session.complete();
                                }
                                completion.complete(null);
                                return;
                            }
                            modelScope.fail(error);
                            logger.error("Semantic Kernel chat error: {}", error.getMessage());
                            if (!session.isClosed()) {
                                session.error(org.atmosphere.ai.ProviderErrorClassifier.wrap(error));
                            }
                            completion.complete(null);
                        },
                        // An empty result (no message at all) still ends the turn.
                        () -> {
                            if (!completion.isDone()) {
                                modelScope.complete(lastUsage.get());
                                if (!session.isClosed()) {
                                    session.complete();
                                }
                                completion.complete(null);
                            }
                        });
    }

    /** Whether {@code message} carries tool calls. */
    private static boolean asksForTools(ChatMessageContent<?> message) {
        var calls = FunctionCallContent.getFunctionCalls(message);
        return calls != null && !calls.isEmpty();
    }

    /** The last {@link AuthorRole#ASSISTANT} message of {@code messages}, or {@code null}. */
    private static ChatMessageContent<?> lastAssistantMessage(java.util.List<ChatMessageContent<?>> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message != null && message.getAuthorRole() == AuthorRole.ASSISTANT) {
                return message;
            }
        }
        return null;
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
        extractTokenUsage(frame.getMetadata(), session, lastUsage);
    }

    private static void extractTokenUsage(
            FunctionResultMetadata<?> metadata,
            StreamingSession session,
            java.util.concurrent.atomic.AtomicReference<TokenUsage> lastUsage) {
        try {
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
