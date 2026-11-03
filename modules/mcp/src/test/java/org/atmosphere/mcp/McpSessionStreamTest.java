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
package org.atmosphere.mcp;

import org.atmosphere.mcp.runtime.McpSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session's live GET stream: a server-initiated message goes out at once
 * on an open stream and is queued only when there is none.
 */
public class McpSessionStreamTest {

    @Test
    public void aMessageIsWrittenToTheOpenStreamAtOnce() {
        var session = new McpSession();
        var written = new ArrayList<String>();
        session.attachStream(written::add);

        session.sendOrQueue("{\"id\":1}");

        assertEquals(List.of("{\"id\":1}"), written);
        assertEquals(0, session.pendingCount(), "nothing is left for a reconnect to replay");
    }

    @Test
    public void attachingReplaysWhatWasQueuedInOrder() {
        var session = new McpSession();
        session.sendOrQueue("a");
        session.sendOrQueue("b");
        assertEquals(2, session.pendingCount());

        var written = new ArrayList<String>();
        session.attachStream(written::add);

        assertEquals(List.of("a", "b"), written);
        assertEquals(0, session.pendingCount());
    }

    @Test
    public void aFailedWriteDetachesTheStreamAndQueuesTheMessage() {
        var session = new McpSession();
        session.attachStream(message -> {
            throw new IOException("client gone");
        });

        session.sendOrQueue("kept");

        assertFalse(session.hasStream());
        assertEquals(List.of("kept"), session.drainPendingNotifications());
    }

    @Test
    public void aReplayThatFailsPartWayKeepsTheRestInOrder() {
        var session = new McpSession();
        session.sendOrQueue("a");
        session.sendOrQueue("b");
        session.sendOrQueue("c");
        var written = new ArrayList<String>();
        session.attachStream(message -> {
            if (message.equals("b")) {
                throw new IOException("dropped mid-replay");
            }
            written.add(message);
        });

        assertEquals(List.of("a"), written);
        assertFalse(session.hasStream());
        assertEquals(List.of("b", "c"), session.drainPendingNotifications());
    }

    @Test
    public void detachingAnOlderStreamKeepsTheNewerOne() {
        var session = new McpSession();
        McpSession.StreamSink older = message -> { };
        var newer = new ArrayList<String>();
        McpSession.StreamSink newerSink = newer::add;
        session.attachStream(older);
        session.attachStream(newerSink);

        session.detachStream(older);
        session.sendOrQueue("x");

        assertTrue(session.hasStream());
        assertEquals(List.of("x"), newer);

        session.detachStream(newerSink);
        session.sendOrQueue("y");
        assertEquals(List.of("y"), session.drainPendingNotifications());
    }

    @Test
    public void closingTheSessionDropsTheStream() {
        var session = new McpSession();
        session.attachStream(message -> { });
        session.close();
        assertFalse(session.hasStream());
    }
}
