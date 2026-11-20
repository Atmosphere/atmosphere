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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Feeds a LangChain4j stream's callbacks to the turn's
 * {@link org.atmosphere.ai.StreamIdleWatchdog} and keeps the stream's
 * {@link StreamingHandle}, so the turn's cancel, and the shared idle bound
 * {@value AiConfig#STREAM_IDLE_TIMEOUT_PROPERTY} that cancels through it, close
 * the connection. LangChain4j's JDK HTTP client bounds a call only until the
 * response headers arrive: a provider that stops sending after its first chunks
 * held the turn, its connection and a thread forever (Correctness Invariant #2).
 *
 * <p>Every callback of the stream, thinking, partial tool calls and raw events
 * included, is activity. The clock itself is the turn's watchdog, opened by
 * {@link org.atmosphere.ai.AbstractAgentRuntime}: tools and approvals between
 * rounds run inside its pause, so they are never idle time.</p>
 *
 * <p>The stream is aborted through the {@link StreamingHandle} LangChain4j hands
 * over with each callback, which closes the connection. On a model Atmosphere
 * built, whose HTTP client is an {@link AtmosphereLc4jHttpClient}, each round is
 * sent through {@link #send}: the client hands the exchange to this tracker, so
 * every byte the provider sends is activity (keep-alive comments included) and
 * the response body, registered once the headers arrive, is closed on abort,
 * before the first event too. A model the application built itself has no such
 * client: there a provider that sends nothing after the response headers hands
 * over no handle, and that connection is left to the provider.</p>
 */
final class StreamActivityTracker implements AtmosphereLc4jHttpClient.Exchange {

    private static final Logger logger = LoggerFactory.getLogger(StreamActivityTracker.class);

    private final Runnable activity;
    private final AtomicReference<StreamingHandle> stream = new AtomicReference<>();
    /** What closes the connections of the round in flight, registered by an {@link AtmosphereLc4jHttpClient}. */
    private final Set<AutoCloseable> connections = ConcurrentHashMap.newKeySet();
    /** Set once the stream was aborted: a handle handed over after that is cancelled on arrival. */
    private volatile boolean aborted;

    /**
     * @param activity records activity on the turn's watchdog; a no-op when the
     *                 turn has none
     */
    StreamActivityTracker(Runnable activity) {
        this.activity = activity;
    }

    /**
     * Wraps the handler of a round about to be sent.
     *
     * @param handler the round's handler
     * @return the handler to give the model
     */
    StreamingChatResponseHandler track(StreamingChatResponseHandler handler) {
        // The previous round's stream has ended: its handle must not stand for this one.
        stream.set(null);
        connections.clear();
        activity.run();
        return new Tracked(handler);
    }

    /**
     * Sends a round: wraps its handler ({@link #track}) and calls the model
     * with this tracker as the exchange's turn, so an
     * {@link AtmosphereLc4jHttpClient} under the model reports to it.
     *
     * @param model   the model
     * @param request the round's request
     * @param handler the round's handler
     */
    void send(dev.langchain4j.model.chat.StreamingChatModel model, dev.langchain4j.model.chat.request.ChatRequest request,
              StreamingChatResponseHandler handler) {
        var tracked = track(handler);
        AtmosphereLc4jHttpClient.sending(this, () -> model.chat(request, tracked));
    }

    @Override
    public void activity() {
        activity.run();
    }

    @Override
    public void register(AutoCloseable connection) {
        connections.add(connection);
        if (aborted) {
            close(connection);
        }
    }

    private static void close(AutoCloseable connection) {
        try {
            connection.close();
        } catch (Exception e) {
            logger.debug("Closing the LangChain4j connection failed", e);
        }
    }

    /**
     * Aborts the stream in flight, which closes its connection: through the
     * response body or subscription an {@link AtmosphereLc4jHttpClient}
     * registered, and the {@code StreamingHandle} the stream handed over. A
     * stream that has handed over neither yet is aborted when it does; on a
     * model the application built, one that never does (no event after the
     * response headers) is left to the provider.
     */
    void abortStream() {
        aborted = true;
        cancel(stream.get());
        connections.forEach(StreamActivityTracker::close);
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

    private void keep(StreamingHandle handle) {
        if (handle != null) {
            stream.set(handle);
            if (aborted) {
                cancel(handle);
            }
        }
    }

    /** Records activity and keeps the stream's handle. */
    private final class Tracked implements StreamingChatResponseHandler {

        private final StreamingChatResponseHandler delegate;

        Tracked(StreamingChatResponseHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            activity.run();
            delegate.onPartialResponse(partialResponse);
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            activity.run();
            keep(context != null ? context.streamingHandle() : null);
            delegate.onPartialResponse(partialResponse, context);
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
            activity.run();
            delegate.onPartialThinking(partialThinking);
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
            activity.run();
            keep(context != null ? context.streamingHandle() : null);
            delegate.onPartialThinking(partialThinking, context);
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
            activity.run();
            delegate.onPartialToolCall(partialToolCall);
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
            activity.run();
            keep(context != null ? context.streamingHandle() : null);
            delegate.onPartialToolCall(partialToolCall, context);
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall completeToolCall) {
            activity.run();
            delegate.onCompleteToolCall(completeToolCall);
        }

        @Override
        public void onUnmappedRawEvent(Object rawEvent) {
            activity.run();
            delegate.onUnmappedRawEvent(rawEvent);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            activity.run();
            delegate.onCompleteResponse(completeResponse);
        }

        @Override
        public void onError(Throwable error) {
            activity.run();
            delegate.onError(error);
        }
    }
}
