/************************************************************************
 * Licensed under Public Domain (CC0)                                    *
 *                                                                       *
 * To the extent possible under law, the person who associated CC0 with  *
 * this code has waived all copyright and related or neighboring         *
 * rights to this code.                                                  *
 *                                                                       *
 * You should have received a copy of the CC0 legalcode along with this  *
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.*
 ************************************************************************/

/*
 * Copyright 2018 Async-IO.org
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

/*
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.atmosphere.reactive;

import org.atmosphere.util.NonBlockingMutexExecutor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Reactive streams publisher that represents a request.
 */
public class RequestPublisher implements Publisher<ByteBuffer> {

    private static final int MAX_SEQUENTIAL_READS = 8;

    private final ServletInputStream inputStream; // TODO This should be cleared when Publisher is done to allow it to be GC:Ed
    private final int readBufferLimit;
    private final int sequentialReadLimit;

    private final NonBlockingMutexExecutor mutex = new NonBlockingMutexExecutor();

    private Subscriber<? super ByteBuffer> subscriber;
    private long demand = 0;
    private boolean running = true;

    public RequestPublisher(final AsyncContext context, final int readBufferLimit) throws IOException {
        this(context, readBufferLimit, MAX_SEQUENTIAL_READS);
    }

    public RequestPublisher(final AsyncContext context, final int readBufferLimit, final int sequentialReadLimit) throws IOException {
        this.inputStream = context.getRequest().getInputStream();
        this.readBufferLimit = readBufferLimit;
        this.sequentialReadLimit = sequentialReadLimit;
        if (readBufferLimit <= 0) throw new IllegalArgumentException("readBufferLimit must be greater than 0");
        if (sequentialReadLimit <= 0) throw new IllegalArgumentException("sequentialReadLimit must be greater than 0");
    }

    /**
     * Handle the case where the subscriber cancels.
     * <p>
     * It's not clear exactly what happens when you close a servlet input stream, so this doesn't close the input stream,
     * instead, it does nothing. To insert some custom behavior, such as closing the stream or consuming the remainder of
     * the body, override this callback.
     * <p>
     * Note that consuming the remainder of the body shouldn't be necessary, servlet containers should consume the
     * remainder of the body when the response is committed.
     */
    protected void handleCancel() {
    }

    private void maybeRead() {
        int readsLeft = sequentialReadLimit;
        ByteBuffer buffer = null;
        while (running && demand > 0 && readsLeft > 0 && inputStream.isReady()) {
            readsLeft -= 1;
            try {
                if (buffer == null) buffer = ByteBuffer.allocate(readBufferLimit);
                final int length = inputStream.read(buffer.array());
                switch (length) {
                    case -1:
                        handleOnComplete();
                        break;
                    case 0:
                        break;
                    default:
                        buffer.limit(length);
                        subscriber.onNext(buffer);
                        buffer = null;
                        demand -= 1;
                        break;
                }
            } catch (IOException e) {
                handleError(e);
            }
        }

        // If data throughput is really high, and the demand is very high (generally not, but in the TCK there is some
        // Integer.MAX_VALUE demand) the loop above can starve all other tasks from running (including, for example,
        // cancel signals). So we limit the number of sequential reads that we do, and if we reach that limit, we resubmit
        // to do any further reads, but giving the opportunity for other tasks to execute.
        if (readsLeft == 0) {
            mutex.execute(this::maybeRead);
        }
    }

    private void handleError(final Throwable t) {
        if (running) {
            running = false;
            subscriber.onError(t);
            subscriber = null;
        }
    }

    private void handleOnComplete() {
        if (running) {
            running = false;
            subscriber.onComplete();
            subscriber = null;
        }
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber, "Subscriber passed to subscribe must not be null");
        mutex.execute(() -> {
            if (this.subscriber == null) {
                this.subscriber = subscriber;
                inputStream.setReadListener(new Listener());
                subscriber.onSubscribe(new RequestSubscription());
            } else if (this.subscriber.equals(subscriber)) {
                handleError(new IllegalStateException("Attempted to subscribe this Subscriber more than once for the same Publisher"));
            } else {
                subscriber.onSubscribe(new Subscription() {
                    @Override
                    public void request(long n) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(new IllegalStateException("This publisher only supports one subscriber"));
            }
        });
    }

    private final class RequestSubscription implements Subscription {
        @Override
        public void request(final long n) {
            mutex.execute(() -> {
                if (running) {
                    if (n <= 0) {
                        handleError(new IllegalArgumentException("Reactive streams 3.9 spec violation: non-positive subscription request"));
                    } else {
                        final long old = demand;
                        if (old < Long.MAX_VALUE) {
                            demand = ((old + n) < 0) ? Long.MAX_VALUE : (old + n); // Overflow protection
                        }
                        if (old == 0) {
                            maybeRead();
                        }
                    }
                }
            });
        }

        @Override
        public void cancel() {
            mutex.execute(() -> {
                if (running) {
                    subscriber = null;
                    running = false;
                    handleCancel();
                }
            });
        }
    }

    private class Listener implements ReadListener {
        @Override
        public void onDataAvailable() throws IOException {
            mutex.execute(RequestPublisher.this::maybeRead);
        }

        @Override
        public void onAllDataRead() throws IOException {
            mutex.execute(RequestPublisher.this::handleOnComplete);
        }

        @Override
        public void onError(final Throwable t) {
            mutex.execute(() -> handleError(t));
        }
    }

}
