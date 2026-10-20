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
package org.atmosphere.integrationtests.ai.room;

import org.atmosphere.ai.StreamingSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The turns of the room endpoints: a prompt {@code turn:<name>} is answered
 * with {@link #CHUNKS} text chunks and {@code complete}; {@code hold:<name>}
 * sends {@link #HELD_AFTER} of them, waits for {@link #RELEASE}, then sends
 * the rest and completes; {@code stream:<name>} sends {@link #STREAM_CHUNKS}
 * chunks one every millisecond or so, as a model streams a long answer.
 */
public final class RoomTurns {

    /** The text chunks of a turn. */
    public static final int CHUNKS = 8;
    /** The chunks a {@code hold:} turn sends before it waits. */
    public static final int HELD_AFTER = 3;
    /** The text chunks of a {@code stream:} turn. */
    public static final int STREAM_CHUNKS = 600;
    /** Released to let a {@code hold:} turn finish. */
    public static volatile CountDownLatch RELEASE = new CountDownLatch(1);

    private RoomTurns() {
    }

    public static void answer(String message, StreamingSession session) {
        var name = message.substring(message.indexOf(':') + 1);
        if (message.startsWith("stream:")) {
            for (var i = 0; i < STREAM_CHUNKS; i++) {
                session.send(name + "-" + i + " ");
                try {
                    Thread.sleep(0, 500_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    session.error(e);
                    return;
                }
            }
            session.complete();
            return;
        }
        for (var i = 0; i < CHUNKS; i++) {
            if (message.startsWith("hold:") && i == HELD_AFTER) {
                try {
                    if (!RELEASE.await(30, TimeUnit.SECONDS)) {
                        session.error(new IllegalStateException("never released"));
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    session.error(e);
                    return;
                }
            }
            session.send(name + "-" + i + " ");
        }
        session.complete();
    }
}
