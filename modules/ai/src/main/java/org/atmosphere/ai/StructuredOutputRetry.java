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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self-healing structured-output reprompt loop.
 *
 * <p>{@link StructuredOutputCapturingSession} parses the final response but
 * cannot re-invoke the model on a parse failure — it is a one-way streaming
 * decorator with no reference to the runtime or the request. This engine sits at
 * the runtime-invocation seam instead: it runs each attempt against an internal
 * {@link BufferingSession}, validates the buffered text against the schema, and
 * on failure re-invokes the runtime with the validation error appended as
 * feedback. Only the first attempt that validates is replayed through the real
 * decorator chain ({@code finalTarget}); intermediate malformed attempts never
 * reach the client, memory, or guardrails.</p>
 *
 * <p>The loop is bounded by {@code maxRetries} and <strong>fails closed</strong>:
 * if the final attempt still does not parse, the session receives a
 * {@link StructuredOutputParser.StructuredOutputException} — never a silent
 * empty success. Transport/runtime errors (as opposed to schema failures) are
 * <em>not</em> reprompted here; they propagate immediately, leaving
 * {@link RetryPolicy}-style transient-error backoff to the runtime adapter.</p>
 *
 * <p>Token usage from every attempt (including the failed ones) is accumulated
 * and forwarded so cost and metrics reflect the true spend of the self-healing
 * round-trips.</p>
 */
final class StructuredOutputRetry {

    private static final Logger logger = LoggerFactory.getLogger(StructuredOutputRetry.class);

    private StructuredOutputRetry() {
    }

    /**
     * Run the reprompt loop on a worker thread and return a handle the caller
     * can await/cancel — drop-in for {@code runtime.executeWithHandle(...)}.
     *
     * @param runtime      the underlying runtime to (re)invoke
     * @param context      the original execution context (its message is the
     *                     anchor the reprompt appends to)
     * @param finalTarget  the fully-decorated session the successful attempt is
     *                     replayed through (includes the
     *                     {@link StructuredOutputCapturingSession} that emits the
     *                     structured events)
     * @param parser       the structured-output parser
     * @param responseType the declared response type
     * @param maxRetries   additional reprompt attempts after the first
     */
    static ExecutionHandle executeWithHandle(AgentRuntime runtime,
                                             AgentExecutionContext context,
                                             StreamingSession finalTarget,
                                             StructuredOutputParser parser,
                                             Class<?> responseType,
                                             int maxRetries) {
        var cancelled = new AtomicBoolean();
        var currentHandle = new AtomicReference<ExecutionHandle>();
        var done = new CompletableFuture<Void>();
        Thread.ofVirtual()
                .name("structured-retry-" + finalTarget.sessionId())
                .start(() -> {
                    try {
                        runLoop(runtime, context, finalTarget, parser, responseType,
                                maxRetries, cancelled, currentHandle);
                    } catch (RuntimeException t) {
                        safeError(finalTarget, t);
                    } finally {
                        done.complete(null);
                    }
                });
        return new ExecutionHandle() {
            @Override
            public void cancel() {
                cancelled.set(true);
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

    private static void runLoop(AgentRuntime runtime, AgentExecutionContext context,
                                StreamingSession finalTarget, StructuredOutputParser parser,
                                Class<?> responseType, int maxRetries,
                                AtomicBoolean cancelled, AtomicReference<ExecutionHandle> currentHandle) {
        var attemptContext = context;
        long inputTotal = 0;
        long outputTotal = 0;
        long usageTotal = 0;
        String model = null;

        for (int attempt = 0; ; attempt++) {
            if (cancelled.get()) {
                finalTarget.error(new CancellationException("structured-output retry cancelled"));
                return;
            }

            var buffer = new BufferingSession(finalTarget.sessionId());
            var handle = runtime.executeWithHandle(attemptContext, buffer);
            currentHandle.set(handle);
            try {
                handle.whenDone().join();
            } catch (CompletionException ce) {
                // The attempt errored; the runtime already routed the cause to
                // buffer.error(...). Fall through to the failure check below.
                if (logger.isTraceEnabled()) {
                    logger.trace("Structured-retry attempt {} completed exceptionally", attempt, ce);
                }
            }

            var usage = buffer.usage();
            if (usage != null) {
                inputTotal += usage.input();
                outputTotal += usage.output();
                usageTotal += usage.total();
                model = usage.model();
            }

            if (buffer.failure() != null) {
                // Transport / runtime error — not a schema problem. Propagate
                // immediately; transient-error backoff is the adapter's job.
                forwardUsage(finalTarget, inputTotal, outputTotal, usageTotal, model);
                finalTarget.error(buffer.failure());
                return;
            }

            var text = buffer.text();
            try {
                parser.parse(text, responseType);
            } catch (StructuredOutputParser.StructuredOutputException e) {
                if (attempt >= maxRetries || cancelled.get()) {
                    logger.warn("Structured output failed schema validation after {} attempt(s); "
                            + "failing closed", attempt + 1);
                    forwardUsage(finalTarget, inputTotal, outputTotal, usageTotal, model);
                    finalTarget.error(new StructuredOutputParser.StructuredOutputException(
                            "structured output did not match schema after " + (attempt + 1)
                                    + " attempt(s): " + e.getMessage(), e));
                    return;
                }
                logger.info("Structured output parse failed (attempt {}/{}), re-prompting: {}",
                        attempt + 1, maxRetries + 1, e.getMessage());
                attemptContext = context.withMessage(
                        reprompt(context.message(), text, e.getMessage(), parser, responseType));
                continue;
            }

            // Validated — replay the good text through the real decorator chain
            // so StructuredOutputCapturingSession emits the structured events and
            // memory / guardrails / metrics see exactly the response that shipped.
            forwardUsage(finalTarget, inputTotal, outputTotal, usageTotal, model);
            finalTarget.send(text);
            finalTarget.complete();
            return;
        }
    }

    private static void forwardUsage(StreamingSession target, long input, long output,
                                     long total, String model) {
        if (input == 0 && output == 0 && total == 0) {
            return;
        }
        var effectiveTotal = total > 0 ? total : input + output;
        target.usage(TokenUsage.of(input, output, effectiveTotal, model));
    }

    private static String reprompt(String originalMessage, String badOutput, String error,
                                   StructuredOutputParser parser, Class<?> responseType) {
        return "Your previous response did not match the required JSON schema.\n"
                + "Validation error: " + error + "\n\n"
                + "Required schema:\n" + parser.schemaInstructions(responseType) + "\n\n"
                + "Your previous (invalid) response was:\n" + badOutput + "\n\n"
                + "Respond again with ONLY a single valid JSON object matching the schema. "
                + "Do not include prose, explanation, or markdown code fences.\n\n"
                + "Original request:\n" + (originalMessage == null ? "" : originalMessage);
    }

    private static void safeError(StreamingSession target, Throwable t) {
        try {
            target.error(t);
        } catch (RuntimeException suppressed) {
            logger.error("Failed to route structured-retry error to session", suppressed);
        }
    }

    /**
     * Captures a single attempt's text, usage, and terminal state without
     * forwarding anything downstream. Structured events emitted by the runtime
     * are irrelevant to an attempt (the buffer validates raw text), so
     * {@code emit}/{@code sendContent} text variants are folded into the buffer
     * and everything else is dropped.
     */
    private static final class BufferingSession implements StreamingSession {
        private final String sessionId;
        private final StringBuilder buffer = new StringBuilder();
        private volatile TokenUsage usage;
        private volatile Throwable failure;
        private volatile boolean closed;

        BufferingSession(String sessionId) {
            this.sessionId = "retry-attempt-" + sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public synchronized void send(String text) {
            if (text != null) {
                buffer.append(text);
            }
        }

        @Override
        public void sendContent(Content content) {
            if (content instanceof Content.Text text) {
                send(text.text());
            }
        }

        @Override
        public void emit(AiEvent event) {
            if (event instanceof AiEvent.TextDelta delta) {
                send(delta.text());
            }
        }

        @Override
        public void usage(TokenUsage tokenUsage) {
            this.usage = tokenUsage;
        }

        @Override
        public void sendMetadata(String key, Object value) {
            // dropped — attempt-internal
        }

        @Override
        public void progress(String message) {
            // dropped — attempt-internal
        }

        @Override
        public void complete() {
            closed = true;
        }

        @Override
        public void complete(String summary) {
            if (summary != null) {
                send(summary);
            }
            closed = true;
        }

        @Override
        public void error(Throwable t) {
            this.failure = t;
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        synchronized String text() {
            return buffer.toString();
        }

        TokenUsage usage() {
            return usage;
        }

        Throwable failure() {
            return failure;
        }
    }
}
