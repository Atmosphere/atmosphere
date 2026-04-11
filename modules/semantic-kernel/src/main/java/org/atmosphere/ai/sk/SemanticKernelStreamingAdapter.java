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
     * Subscribe to the SK flux synchronously, forwarding every content frame
     * through the session. Blocks the calling thread until the flux terminates.
     * Safe under virtual threads because the subscription is effectively a
     * synchronous loop.
     */
    static void drain(Flux<StreamingChatContent<?>> flux, StreamingSession session) {
        flux.takeWhile(ignored -> !session.isClosed())
                .doOnNext(frame -> forwardFrame(frame, session))
                .doOnError(error -> {
                    logger.error("Semantic Kernel streaming error: {}", error.getMessage());
                    if (!session.isClosed()) {
                        session.error(error);
                    }
                })
                .doOnComplete(() -> {
                    if (!session.isClosed()) {
                        session.complete();
                    }
                })
                .blockLast();
    }

    private static void forwardFrame(StreamingChatContent<?> frame, StreamingSession session) {
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
        extractTokenUsage(frame, session);
    }

    private static void extractTokenUsage(StreamingChatContent<?> frame, StreamingSession session) {
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
            if (total == 0L && (input > 0L || output > 0L)) {
                total = input + output;
            }
            var tokenUsage = new TokenUsage(input, output, 0L, total, null);
            if (tokenUsage.hasCounts()) {
                session.usage(tokenUsage);
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
