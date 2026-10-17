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

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * The terminal of a run the framework cut short. It travels down a session
 * chain through {@link StreamingSession#error(Throwable)}, so every decorator
 * that finalizes on a terminal call sees it, and the wire sessions turn it into
 * a {@code cancelled} frame whose {@code data} is the {@link Reason#wire()} code:
 *
 * <pre>
 * {"type":"cancelled","data":"disconnect","sessionId":"abc-123","seq":7}
 * </pre>
 *
 * <p>A cancelled run ends with that one frame: never a {@code complete} or an
 * {@code error} besides it. Protocol bridges map it to their own cancel state
 * where the protocol has one (A2A {@code TASK_STATE_CANCELED}).</p>
 */
public final class RunCancelledException extends CancellationException {

    /** Wire type of the terminal frame of a cancelled run. */
    public static final String FRAME_TYPE = "cancelled";

    /** Why a run was cut short. */
    public enum Reason {
        /** The client that started the run went away and did not come back. */
        DISCONNECT("disconnect",
                "Run cancelled: the client that started it disconnected before the model call finished"),
        /** An operator cancelled the model call through the admin plane. */
        OPERATOR("operator",
                "Run cancelled: an operator cancelled the model call before it finished"),
        /** The idle-session sweep reaped the session. */
        SWEEP("sweep",
                "Run cancelled: the session expired before the model call finished"),
        /**
         * A run whose {@code @Prompt} method returned with its session still
         * open outlived the time it may run on after that return.
         */
        TIMEOUT("timeout",
                "Run cancelled: it was still open past its time limit after its @Prompt method returned"),
        /** The code that dispatched the run cancelled it (an interrupted pipeline caller). */
        CALLER("caller",
                "Run cancelled: the caller cancelled the model call before it finished");

        private final String wire;
        private final String message;

        Reason(String wire, String message) {
            this.wire = wire;
            this.message = message;
        }

        /** The code a {@code cancelled} frame carries as its {@code data}. */
        public String wire() {
            return wire;
        }

        /** A sentence describing the cancel, for logs and protocols that carry text. */
        public String message() {
            return message;
        }

        /**
         * The reason a wire code names.
         *
         * @param wire a {@link #wire()} code
         * @return the reason, or empty for an unknown or null code
         */
        public static Optional<Reason> fromWire(String wire) {
            for (var reason : values()) {
                if (reason.wire.equals(wire)) {
                    return Optional.of(reason);
                }
            }
            return Optional.empty();
        }
    }

    private final Reason reason;

    public RunCancelledException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message());
        this.reason = reason;
    }

    /** Why the run was cut short. */
    public Reason reason() {
        return reason;
    }

    /**
     * The cancel reason a terminal failure carries, looking through the
     * {@code CompletionException} wrapping a future adds.
     *
     * @param failure a terminal failure, may be null
     * @return the reason when {@code failure} is a run cancellation
     */
    public static Optional<Reason> reasonOf(Throwable failure) {
        var current = failure;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (current instanceof RunCancelledException cancelled) {
                return Optional.of(cancelled.reason);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
