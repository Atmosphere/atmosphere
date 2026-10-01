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
package org.atmosphere.ai.decision.typesafe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Collects a response body into a byte array, failing with
 * {@link TooLargeException} and cancelling the subscription as soon as the body
 * passes {@code limit} bytes, so a misbehaving server cannot make the adapter
 * buffer without bound (Correctness Invariant #3).
 */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    /** The body passed the limit. */
    static final class TooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        TooLargeException(long limit) {
            super("response body exceeds " + limit + " bytes");
        }
    }

    private final long limit;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(long limit) {
        this.limit = limit;
    }

    /** A handler whose every response is read through a fresh bounded subscriber. */
    static HttpResponse.BodyHandler<byte[]> handler(long limit) {
        return info -> new BoundedBodySubscriber(limit);
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (result.isDone()) {
            return;
        }
        for (var item : items) {
            var size = item.remaining();
            if (buffer.size() + (long) size > limit) {
                subscription.cancel();
                result.completeExceptionally(new TooLargeException(limit));
                return;
            }
            var bytes = new byte[size];
            item.get(bytes);
            buffer.write(bytes, 0, size);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        result.complete(buffer.toByteArray());
    }
}
