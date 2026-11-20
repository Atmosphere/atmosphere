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

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelineNextSyncPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.CoreUtils;
import com.azure.core.util.FluxUtil;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * What one Atmosphere turn tells the client Atmosphere builds
 * ({@link SemanticKernelOpenAiClientFactory#forEndpoint}) through the Reactor
 * context of its subscription: the turn's idle activity, fed every chunk of the
 * response body, and a signal the turn's handle raises when it is cancelled or
 * its idle bound fires.
 *
 * <p>The Azure SDK's chat stream ends in {@code .cache()}
 * ({@code OpenAIServerSentEvents}), so disposing a subscription never reaches
 * the HTTP exchange: the request kept running, its connection open, and was
 * even retried. {@link Policy}, which the factory installs, cuts the exchange
 * itself on that signal (the response before its headers, the body after
 * them), so the transport lets the connection go. A client built elsewhere has
 * no such policy and ignores the context.</p>
 */
final class SemanticKernelTurnTransport {

    /** The Reactor context key the turn is carried under. */
    static final String CONTEXT_KEY = SemanticKernelTurnTransport.class.getName();

    private final Runnable activity;
    private final Sinks.Empty<Void> aborted = Sinks.empty();

    SemanticKernelTurnTransport(Runnable activity) {
        this.activity = activity;
    }

    /** Ends the turn's HTTP exchange, wherever it is. Idempotent. */
    void abort() {
        aborted.tryEmitEmpty();
    }

    /** @return the Reactor context carrying this turn */
    Context context() {
        return Context.of(CONTEXT_KEY, this);
    }

    private Mono<Void> aborted() {
        return aborted.asMono();
    }

    /**
     * The pipeline policy of the client Atmosphere builds. Per call, so the
     * signal ends the whole call, its retries included. A response of an
     * {@code text/event-stream} body has its SSE comment lines (a provider's
     * keep-alive, {@code : keep-alive}) removed before the Azure SDK's parser
     * sees them, which rejects them ({@code Invalid Json format keep-alive})
     * and failed the turn; they still count as activity.
     */
    static final class Policy implements HttpPipelinePolicy {

        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
            return Mono.deferContextual(view -> {
                SemanticKernelTurnTransport turn = view.getOrDefault(CONTEXT_KEY, null);
                var call = next.process();
                if (turn != null) {
                    call = call.takeUntilOther(turn.aborted());
                }
                return call.map(response -> new TurnResponse(response, turn));
            });
        }

        @Override
        public HttpResponse processSync(HttpPipelineCallContext context, HttpPipelineNextSyncPolicy next) {
            // A synchronous call has no Reactor context to carry a turn.
            return new TurnResponse(next.processSync(), null);
        }

        @Override
        public HttpPipelinePosition getPipelinePosition() {
            return HttpPipelinePosition.PER_CALL;
        }
    }

    /** The response, its body cut by the turn's signal and stripped of SSE comments. */
    private static final class TurnResponse extends HttpResponse {

        private final HttpResponse delegate;
        private final SemanticKernelTurnTransport turn;
        private final boolean eventStream;

        TurnResponse(HttpResponse delegate, SemanticKernelTurnTransport turn) {
            super(delegate.getRequest());
            this.delegate = delegate;
            this.turn = turn;
            var type = delegate.getHeaders().getValue(HttpHeaderName.CONTENT_TYPE);
            this.eventStream = type != null && type.toLowerCase(Locale.ROOT).startsWith("text/event-stream");
        }

        @Override
        public int getStatusCode() {
            return delegate.getStatusCode();
        }

        /** {@inheritDoc} */
        @Deprecated
        @Override
        public String getHeaderValue(String name) {
            return delegate.getHeaders().getValue(HttpHeaderName.fromString(name));
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            var body = delegate.getBody();
            if (turn != null) {
                body = body.takeUntilOther(turn.aborted()).doOnNext(ignored -> turn.activity.run());
            }
            if (eventStream) {
                var filter = new CommentFilter();
                body = body.map(filter::apply).filter(ByteBuffer::hasRemaining);
            }
            return body;
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return FluxUtil.collectBytesInByteBufferStream(getBody());
        }

        @Override
        public Mono<String> getBodyAsString() {
            return getBodyAsByteArray().map(bytes ->
                    CoreUtils.bomAwareToString(bytes, delegate.getHeaders().getValue(HttpHeaderName.CONTENT_TYPE)));
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return getBodyAsByteArray().map(bytes -> new String(bytes, charset));
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Removes SSE comment lines (a line that starts with {@code :}, with its
     * line terminator) from a byte stream, across chunk boundaries. Each chunk
     * comes back in a buffer of its own, exactly sized: the Azure SDK's parser
     * reads a chunk's whole {@code array()}.
     */
    static final class CommentFilter {
        private boolean lineStart = true;
        private boolean inComment;
        /** A comment ended on {@code \r}: the {@code \n} of its CRLF goes with it. */
        private boolean swallowLf;

        ByteBuffer apply(ByteBuffer chunk) {
            var out = new ByteArrayOutputStream(chunk.remaining());
            var source = chunk.duplicate();
            while (source.hasRemaining()) {
                var b = source.get();
                var terminator = b == '\n' || b == '\r';
                if (swallowLf) {
                    swallowLf = false;
                    if (b == '\n') {
                        continue;
                    }
                }
                if (inComment) {
                    if (terminator) {
                        inComment = false;
                        lineStart = true;
                        swallowLf = b == '\r';
                    }
                    continue;
                }
                if (lineStart && b == ':') {
                    inComment = true;
                    continue;
                }
                out.write(b);
                lineStart = terminator;
            }
            return ByteBuffer.wrap(out.toByteArray());
        }
    }
}
