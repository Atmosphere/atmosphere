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

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.atmosphere.ai.llm.ActivityInputStream;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * The LangChain4j {@link HttpClient} under the models Atmosphere builds: it
 * hands each streamed exchange to the turn that sent it, so the turn's
 * {@link org.atmosphere.ai.StreamIdleWatchdog} sees every byte the provider
 * sends (SSE keep-alive comments included, which LangChain4j's parser drops
 * before any handler callback), and the turn's cancel closes the connection
 * even before the stream's first event handed over a {@code StreamingHandle}.
 *
 * <p>All four entry points are wrapped: {@code execute(request, listener)} and
 * {@code execute(request, parser, listener)} (the handler path of
 * {@code OpenAiStreamingChatModel}), {@code stream(request)} and
 * {@code stream(request, parser)} (its publisher path). The plain
 * {@code execute(request)} and {@code executeAsync(request)} are not streamed
 * and go to the delegate unchanged.</p>
 *
 * <p>The turn is the {@link Exchange} {@link #sending} binds while the turn's
 * own thread calls the model, read once when the request enters this client
 * and closed over from then on: the JDK client parses the body on one of its
 * executor threads, which knows no turn.</p>
 */
final class AtmosphereLc4jHttpClient implements HttpClient {

    /**
     * What a turn gets from the exchange it sent: activity for every byte and
     * the handle that closes the connection.
     */
    interface Exchange {
        /** Records activity on the turn's clock. */
        void activity();

        /**
         * Registers what closes the exchange's connection: its response body,
         * or the subscription of its event stream. Called once the response
         * headers arrived.
         *
         * @param connection closes the connection; closed at once when the
         *                   turn was aborted already
         */
        void register(AutoCloseable connection);
    }

    /**
     * The turn the calling thread is sending a request for. Set only around
     * the synchronous model call on the turn's own thread ({@link #sending})
     * and read only when a request enters this client on that thread; never
     * read on the thread that parses the response.
     */
    private static final ThreadLocal<Exchange> SENDING = new ThreadLocal<>();

    private final HttpClient delegate;

    AtmosphereLc4jHttpClient(HttpClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Runs {@code send} (a model call) with {@code exchange} as the turn of
     * every request it sends through an {@code AtmosphereLc4jHttpClient}.
     *
     * @param exchange the turn sending
     * @param send     the synchronous model call
     */
    static void sending(Exchange exchange, Runnable send) {
        var previous = SENDING.get();
        SENDING.set(exchange);
        try {
            send.run();
        } finally {
            if (previous == null) {
                SENDING.remove();
            } else {
                SENDING.set(previous);
            }
        }
    }

    /**
     * @param delegate the builder of the client to wrap
     * @return a builder whose clients are wrapped
     */
    static HttpClientBuilder builder(HttpClientBuilder delegate) {
        return new Builder(delegate);
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(request);
    }

    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
        return delegate.executeAsync(request);
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventListener listener) {
        execute(request, new DefaultServerSentEventParser(), listener);
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        var exchange = SENDING.get();
        delegate.execute(request, exchange == null ? parser : new TrackedParser(parser, exchange), listener);
    }

    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request) {
        return stream(request, new DefaultServerSentEventParser());
    }

    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
        var exchange = SENDING.get();
        if (exchange == null) {
            return delegate.stream(request, parser);
        }
        var publisher = delegate.stream(request, new TrackedParser(parser, exchange));
        return subscriber -> publisher.subscribe(new TrackedSubscriber(subscriber, exchange));
    }

    /** Counts every byte the parser reads and registers the body it reads from. */
    private static final class TrackedParser implements ServerSentEventParser {
        private final ServerSentEventParser delegate;
        private final Exchange exchange;

        TrackedParser(ServerSentEventParser delegate, Exchange exchange) {
            this.delegate = delegate;
            this.exchange = exchange;
        }

        @Override
        public void parse(InputStream body, ServerSentEventListener listener) {
            // The headers arrived: closing this body closes the connection.
            var tracked = new ActivityInputStream(body, exchange::activity);
            exchange.register(tracked);
            exchange.activity();
            delegate.parse(tracked, listener);
        }

        @Override
        public Incremental incremental() {
            var incremental = delegate.incremental();
            return new Incremental() {
                @Override
                public List<ServerSentEvent> feed(ByteBuffer bytes) {
                    exchange.activity();
                    return incremental.feed(bytes);
                }

                @Override
                public List<ServerSentEvent> flush() {
                    return incremental.flush();
                }
            };
        }
    }

    /**
     * Registers the subscription of a streamed exchange: cancelling it ends
     * LangChain4j's publisher, which cancels the pending request or the body
     * subscription and so closes the connection.
     */
    private static final class TrackedSubscriber implements Flow.Subscriber<HttpStreamingEvent> {
        private final Flow.Subscriber<? super HttpStreamingEvent> delegate;
        private final Exchange exchange;

        TrackedSubscriber(Flow.Subscriber<? super HttpStreamingEvent> delegate, Exchange exchange) {
            this.delegate = delegate;
            this.exchange = exchange;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            exchange.register(subscription::cancel);
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(HttpStreamingEvent item) {
            exchange.activity();
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }

    /** Builds the delegate's client and wraps it. */
    private static final class Builder implements HttpClientBuilder {
        private final HttpClientBuilder delegate;

        Builder(HttpClientBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Duration connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            delegate.connectTimeout(timeout);
            return this;
        }

        @Override
        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            delegate.readTimeout(timeout);
            return this;
        }

        @Override
        public HttpClient build() {
            return new AtmosphereLc4jHttpClient(delegate.build());
        }
    }
}
