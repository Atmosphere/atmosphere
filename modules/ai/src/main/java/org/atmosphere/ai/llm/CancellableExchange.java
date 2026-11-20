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
package org.atmosphere.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serial;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A cancel flag that also closes the model call it stands for. It is an
 * {@link AtomicBoolean}, so it passes through every {@code AtomicBoolean
 * cancelled} parameter a client already takes; a client that finds one there
 * ({@code cancelled instanceof CancellableExchange}) registers what ends its
 * exchange: the {@code sendAsync} future while the request is in flight
 * ({@link #registerSend registerSend(() -> future.cancel(true))}), and the
 * response body once it arrives ({@link #registerBody}). A plain
 * {@code AtomicBoolean} keeps the flag-only behaviour.
 *
 * <p>{@link #abort()} sets the flag and closes both targets registered at that
 * moment. A target registered after the abort is closed at once, so an abort
 * that lands between sending and receiving still releases the connection. No
 * thread is interrupted, so a tool body or an approval wait on the same thread
 * is never cut short by an abort.</p>
 *
 * <p>Only {@link #abort()} closes: {@link #set(boolean) set(true)} and
 * {@link #compareAndSet compareAndSet} are final on {@code AtomicBoolean}, so
 * they only set the flag; a target registered after them is still closed on
 * registration, since registering reads the flag.</p>
 *
 * <p>Two slots, one per role, so the body registered from a {@code sendAsync}
 * callback thread and the future registered by the calling thread never
 * displace each other, in either order. Registering again in a slot (the next
 * request of the same turn) replaces its previous target without closing it:
 * that exchange is over. Each target is closed at most once by this exchange;
 * closing is best-effort, and a failure is logged, never thrown.</p>
 */
public final class CancellableExchange extends AtomicBoolean {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(CancellableExchange.class);

    private final transient AtomicReference<Closeable> send = new AtomicReference<>();
    private final transient AtomicReference<Closeable> body = new AtomicReference<>();

    /** A flag not yet set, with nothing registered. */
    public CancellableExchange() {
        super(false);
    }

    /**
     * Registers what cancels the request in flight; closes it at once when the
     * exchange was already aborted.
     *
     * @param closeable the {@code sendAsync} future's cancel; ignored when {@code null}
     */
    public void registerSend(Closeable closeable) {
        register(send, closeable);
    }

    /**
     * Registers the response body; closes it at once when the exchange was
     * already aborted.
     *
     * @param closeable the response body; ignored when {@code null}
     */
    public void registerBody(Closeable closeable) {
        register(body, closeable);
    }

    /** Sets the flag and closes the registered targets. Idempotent. */
    public void abort() {
        set(true);
        close(send.getAndSet(null));
        close(body.getAndSet(null));
    }

    /** @return whether the exchange was aborted (or its flag set) */
    public boolean isAborted() {
        return get();
    }

    private void register(AtomicReference<Closeable> slot, Closeable closeable) {
        if (closeable == null) {
            return;
        }
        slot.set(closeable);
        // Read after the store: an abort that ran in between either took the
        // target (and closed it) or is seen here. Only our own target is taken
        // back, so a register never closes, or keeps open, another's.
        if (get() && slot.compareAndSet(closeable, null)) {
            close(closeable);
        }
    }

    private static void close(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException | RuntimeException e) {
            logger.debug("Closing an aborted model exchange failed", e);
        }
    }
}
