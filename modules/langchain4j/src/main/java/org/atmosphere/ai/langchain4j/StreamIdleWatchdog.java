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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.atmosphere.ai.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Ends a turn whose model stream sends nothing for longer than
 * {@value AiConfig#STREAM_IDLE_TIMEOUT_PROPERTY}, and aborts that stream.
 * LangChain4j's JDK HTTP client bounds a call only until the response headers
 * arrive: a provider that stops sending after its first chunks held the turn,
 * its connection and a thread forever (Correctness Invariant #2).
 *
 * <p>The clock runs only while a model call is in flight: it starts when a
 * round's request is sent ({@link #track}) and stops when that round ends, so a
 * tool running or an approval pending between rounds is never idle time. Every
 * callback of the stream, thinking and tool-call deltas included, is activity.</p>
 *
 * <p>One per turn. Its timer is a virtual thread that waits on the turn's done
 * future, so it ends with the turn on every terminal path, and none is started
 * when the bound is off. Once it fires it drops every later callback of the
 * stream, so aborting the stream, which makes LangChain4j report an error of
 * its own, cannot end the turn a second time.</p>
 *
 * <p>The stream is aborted through the {@link StreamingHandle} LangChain4j hands
 * over with each callback, which closes the connection. A provider that sends
 * nothing at all after the response headers hands over no handle: the turn is
 * still ended, but that connection is left to the provider.</p>
 */
final class StreamIdleWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(StreamIdleWatchdog.class);

    private final Duration idleTimeout;
    private final CompletableFuture<Void> done;
    private final AtomicReference<StreamingHandle> stream = new AtomicReference<>();
    private volatile long lastActivityNanos = System.nanoTime();
    private volatile boolean inFlight;
    private volatile boolean fired;
    /** Set once the stream was aborted: a handle handed over after that is cancelled on arrival. */
    private volatile boolean aborted;

    /**
     * @param idleTimeout how long a model call may send nothing; {@link Duration#ZERO} turns the bound off
     * @param done        the turn's done future: the timer ends when it completes
     */
    StreamIdleWatchdog(Duration idleTimeout, CompletableFuture<Void> done) {
        this.idleTimeout = idleTimeout;
        this.done = done;
    }

    /**
     * Starts the timer, unless the bound is off.
     *
     * @param onTimeout ends the turn with the timeout; called at most once, before the stream is aborted
     */
    void start(Consumer<TimeoutException> onTimeout) {
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            return;
        }
        Thread.ofVirtual().name("lc4j-stream-idle-watchdog").start(() -> watch(onTimeout));
    }

    /**
     * Wraps the handler of a round about to be sent, and starts that round's
     * clock.
     *
     * @param handler the round's handler
     * @return the handler to give the model
     */
    StreamingChatResponseHandler track(StreamingChatResponseHandler handler) {
        // The previous round's stream has ended: its handle must not stand for this one.
        stream.set(null);
        lastActivityNanos = System.nanoTime();
        inFlight = true;
        return new Tracked(handler);
    }

    /**
     * Aborts the stream in flight, which closes its connection. A stream that has
     * not handed over its handle yet is aborted when it does; one that never
     * does (no event after the response headers) is left to the provider.
     */
    void abortStream() {
        aborted = true;
        cancel(stream.get());
    }

    /** Ends the clock without firing: the round could not be sent. */
    void stop() {
        inFlight = false;
    }

    private static void cancel(StreamingHandle handle) {
        if (handle != null && !handle.isCancelled()) {
            try {
                handle.cancel();
            } catch (RuntimeException e) {
                logger.debug("Aborting the LangChain4j stream failed", e);
            }
        }
    }

    private void watch(Consumer<TimeoutException> onTimeout) {
        long idleNanos;
        try {
            idleNanos = idleTimeout.toNanos();
        } catch (ArithmeticException e) {
            // Longer than about 292 years: as good as no bound, but still one.
            idleNanos = Long.MAX_VALUE;
        }
        while (!done.isDone()) {
            var waitNanos = inFlight ? idleNanos - (System.nanoTime() - lastActivityNanos) : idleNanos;
            if (inFlight && waitNanos <= 0) {
                fire(onTimeout);
                return;
            }
            try {
                done.get(Math.max(waitNanos, TimeUnit.MILLISECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException e) {
                // Not done yet: check the clock again.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | CancellationException e) {
                // The turn ended, with an error or a cancel: nothing to watch.
                return;
            }
        }
    }

    private void fire(Consumer<TimeoutException> onTimeout) {
        // From here every later callback of the stream is dropped. A callback
        // already past that check (the last event racing the clock) may still
        // end the turn; the session lets one terminal event through.
        fired = true;
        var timeout = new TimeoutException("The model sent nothing for " + idleTimeout
                + " (" + AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY + ")");
        logger.warn("Ending a LangChain4j turn: {}", timeout.getMessage());
        try {
            onTimeout.accept(timeout);
        } finally {
            abortStream();
        }
    }

    private void activity() {
        lastActivityNanos = System.nanoTime();
    }

    private void keep(StreamingHandle handle) {
        if (handle != null) {
            stream.set(handle);
            if (aborted) {
                cancel(handle);
            }
        }
    }

    /** Records activity, keeps the stream's handle and, once the watchdog fired, drops the callback. */
    private final class Tracked implements StreamingChatResponseHandler {

        private final StreamingChatResponseHandler delegate;

        Tracked(StreamingChatResponseHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            activity();
            if (!fired) {
                delegate.onPartialResponse(partialResponse);
            }
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            activity();
            keep(context != null ? context.streamingHandle() : null);
            if (!fired) {
                delegate.onPartialResponse(partialResponse, context);
            }
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
            activity();
            if (!fired) {
                delegate.onPartialThinking(partialThinking);
            }
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
            activity();
            keep(context != null ? context.streamingHandle() : null);
            if (!fired) {
                delegate.onPartialThinking(partialThinking, context);
            }
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
            activity();
            if (!fired) {
                delegate.onPartialToolCall(partialToolCall);
            }
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
            activity();
            keep(context != null ? context.streamingHandle() : null);
            if (!fired) {
                delegate.onPartialToolCall(partialToolCall, context);
            }
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall completeToolCall) {
            activity();
            if (!fired) {
                delegate.onCompleteToolCall(completeToolCall);
            }
        }

        @Override
        public void onUnmappedRawEvent(Object rawEvent) {
            activity();
            if (!fired) {
                delegate.onUnmappedRawEvent(rawEvent);
            }
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            // The round ended: tools and approvals that follow are not idle time.
            inFlight = false;
            if (!fired) {
                delegate.onCompleteResponse(completeResponse);
            }
        }

        @Override
        public void onError(Throwable error) {
            inFlight = false;
            if (!fired) {
                delegate.onError(error);
            } else {
                logger.trace("Dropping the LangChain4j error that followed the idle timeout", error);
            }
        }
    }
}
