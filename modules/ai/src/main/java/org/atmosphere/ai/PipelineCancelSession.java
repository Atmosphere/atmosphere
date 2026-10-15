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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link AiPipeline} twin of {@link AiStreamingSession}'s cancelled
 * terminal (Correctness Invariant #7): once {@link #cancel} marked the run
 * cut short, the first terminal the runtime sends — the {@code complete} a
 * runtime bridge sends on a caller-initiated cancel, or an error of its own —
 * reaches the caller's session as the {@link RunCancelledException} of that
 * reason, and no terminal follows it.
 */
final class PipelineCancelSession extends DelegatingStreamingSession {

    private final AtomicReference<RunCancelledException.Reason> pending = new AtomicReference<>();
    private final AtomicBoolean cancelledSent = new AtomicBoolean();

    PipelineCancelSession(StreamingSession delegate) {
        super(delegate);
    }

    /** The session this one wraps. */
    StreamingSession wrapped() {
        return delegate;
    }

    /** Mark the run cut short; the first later terminal becomes the cancelled one. */
    void cancel(RunCancelledException.Reason reason) {
        pending.compareAndSet(null, reason);
    }

    /**
     * Send the cancelled terminal of a run {@link #cancel} marked, when its
     * runtime ended without sending one. A no-op otherwise.
     */
    void endCancelled() {
        var reason = pending.getAndSet(null);
        if (reason != null && cancelledSent.compareAndSet(false, true)) {
            delegate.error(new RunCancelledException(reason));
        }
    }

    @Override
    public void complete() {
        if (terminalAfterCancel()) {
            return;
        }
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        if (terminalAfterCancel()) {
            return;
        }
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        if (terminalAfterCancel()) {
            return;
        }
        delegate.error(t);
    }

    @Override
    public void emit(AiEvent event) {
        if ((event instanceof AiEvent.Complete || event instanceof AiEvent.Error) && terminalAfterCancel()) {
            return;
        }
        delegate.emit(event);
    }

    /** A cancelled run did not finish: its partial answer must not be committed. */
    @Override
    public boolean hasErrored() {
        return cancelledSent.get() || delegate.hasErrored();
    }

    /**
     * Whether this terminal is consumed by the cancel: it is turned into the
     * cancelled terminal, or dropped when that was already sent.
     */
    private boolean terminalAfterCancel() {
        if (cancelledSent.get()) {
            return true;
        }
        var reason = pending.getAndSet(null);
        if (reason == null) {
            return false;
        }
        if (cancelledSent.compareAndSet(false, true)) {
            delegate.error(new RunCancelledException(reason));
        }
        return true;
    }
}
