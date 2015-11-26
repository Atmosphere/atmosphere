/*
 * Copyright 2015 Async-IO.org
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

package org.atmosphere.cpr;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Simple {@link Future} that can be used when awaiting for a {@link Broadcaster} to finish
 * its broadcast operation to {@link AtmosphereHandler}.
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterFuture<E> implements Future {

    private final CountDownLatch latch;
    private boolean isCancelled;
    private boolean isDone;
    private final E msg;
    private final Future<?> innerFuture;

    public BroadcasterFuture(E msg) {
        this(null, msg);
    }

    public BroadcasterFuture(Future<?> innerFuture, E msg) {
        this(innerFuture, msg, 1);
    }

    public BroadcasterFuture(E msg, int latchCount) {
        this(null, msg, latchCount);
    }

    public BroadcasterFuture(Future<?> innerFuture, E msg, int latchCount) {
        this.msg = msg;
        this.innerFuture = innerFuture;
        if (innerFuture == null) {
            latch = new CountDownLatch(latchCount);
        } else {
            latch = null;
        }
    }

    @Override
    public boolean cancel(boolean b) {

        if (innerFuture != null) {
            return innerFuture.cancel(b);
        }
        isCancelled = true;

        while (latch.getCount() > 0) {
            latch.countDown();
        }
        return isCancelled;
    }

    @Override
    public boolean isCancelled() {

        if (innerFuture != null) {
            return innerFuture.isCancelled();
        }

        return isCancelled;
    }

    @Override
    public boolean isDone() {

        if (innerFuture != null) {
            return innerFuture.isDone();
        }

        isDone = true;
        return isDone;
    }

    /**
     * Invoked when a {@link Broadcaster} completed its broadcast operation.
     */
    public BroadcasterFuture<E> done() {
        isDone = true;

        if (latch != null) {
            latch.countDown();
        }
        return this;
    }

    @Override
    public E get() throws InterruptedException, ExecutionException {
        if (innerFuture != null) {
            return (E) innerFuture.get();
        }

        latch.await();
        return msg;

    }

    @Override
    public E get(long l, TimeUnit tu) throws InterruptedException, ExecutionException, TimeoutException {

        if (innerFuture != null) {
            return (E) innerFuture.get(l, tu);
        }

        boolean isSuccessful = latch.await(l, tu);
        if (!isSuccessful) {
            throw new TimeoutException();
        }
        return msg;
    }
}
