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

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectingSessionTest {

    @Test
    void defaultSessionIdStartsWithCollecting() {
        var session = new CollectingSession();
        assertTrue(session.sessionId().startsWith("collecting-"));
    }

    @Test
    void customSessionId() {
        var session = new CollectingSession("my-session");
        assertEquals("my-session", session.sessionId());
    }

    @Test
    void sendAccumulatesText() {
        var session = new CollectingSession();
        session.send("hello ");
        session.send("world");
        assertEquals("hello world", session.text());
    }

    @Test
    void completeMarksSessionClosed() {
        var session = new CollectingSession();
        assertFalse(session.isClosed());
        session.complete();
        assertTrue(session.isClosed());
    }

    @Test
    void completeWithSummaryAppendsText() {
        var session = new CollectingSession();
        session.send("partial ");
        session.complete("done");
        assertEquals("partial done", session.text());
        assertTrue(session.isClosed());
    }

    @Test
    void completeWithNullSummaryDoesNotAppend() {
        var session = new CollectingSession();
        session.send("data");
        session.complete(null);
        assertEquals("data", session.text());
    }

    @Test
    void errorMarksSessionClosed() {
        var session = new CollectingSession();
        var ex = new RuntimeException("fail");
        session.error(ex);
        assertTrue(session.isClosed());
        assertTrue(session.failed());
        assertEquals(ex, session.failure());
    }

    @Test
    void noErrorMeansNotFailed() {
        var session = new CollectingSession();
        session.complete();
        assertFalse(session.failed());
        assertNull(session.failure());
    }

    @Test
    void awaitReturnsAfterComplete() {
        var session = new CollectingSession();
        session.send("data");
        session.complete();
        assertTrue(session.await(Duration.ofMillis(100)));
    }

    @Test
    void awaitReturnsAfterError() {
        var session = new CollectingSession();
        session.error(new RuntimeException());
        assertTrue(session.await(Duration.ofMillis(100)));
    }

    @Test
    void awaitTimesOutWhenNotCompleted() {
        var session = new CollectingSession();
        assertFalse(session.await(Duration.ofMillis(10)));
    }

    @Test
    void sendMetadataIsNoOp() {
        var session = new CollectingSession();
        session.sendMetadata("key", "value");
        assertEquals("", session.text());
    }

    @Test
    void progressIsNoOp() {
        var session = new CollectingSession();
        session.progress("loading...");
        assertEquals("", session.text());
    }

    @Test
    void doubleCompleteIsIdempotent() {
        var session = new CollectingSession();
        session.complete();
        session.complete();
        assertTrue(session.isClosed());
    }

    @Test
    void textReturnsEmptyByDefault() {
        var session = new CollectingSession();
        assertNotNull(session.text());
        assertEquals("", session.text());
    }
}
