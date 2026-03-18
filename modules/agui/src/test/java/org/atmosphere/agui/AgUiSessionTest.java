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
package org.atmosphere.agui;

import org.atmosphere.agui.runtime.AgUiSession;
import org.atmosphere.protocol.ProtocolSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgUiSessionTest {

    private AgUiSession session;

    @BeforeEach
    void setUp() {
        session = new AgUiSession();
    }

    @Test
    void testCurrentRunIdTracking() {
        assertNull(session.currentRunId(), "Initial runId should be null");

        session.setCurrentRunId("run-123");
        assertEquals("run-123", session.currentRunId());

        session.setCurrentRunId("run-456");
        assertEquals("run-456", session.currentRunId());

        session.setCurrentRunId(null);
        assertNull(session.currentRunId(), "RunId should be clearable to null");
    }

    @Test
    void testCurrentThreadIdTracking() {
        assertNull(session.currentThreadId(), "Initial threadId should be null");

        session.setCurrentThreadId("thread-abc");
        assertEquals("thread-abc", session.currentThreadId());

        session.setCurrentThreadId("thread-xyz");
        assertEquals("thread-xyz", session.currentThreadId());

        session.setCurrentThreadId(null);
        assertNull(session.currentThreadId(), "ThreadId should be clearable to null");
    }

    @Test
    void testSessionExtendsProtocolSession() {
        assertInstanceOf(ProtocolSession.class, session);
    }

    @Test
    void testSessionIdIsGenerated() {
        assertNotNull(session.sessionId());
        assertFalse(session.sessionId().isBlank(), "SessionId should not be blank");
    }

    @Test
    void testSessionIdIsUnique() {
        var session2 = new AgUiSession();
        assertNotNull(session.sessionId());
        assertNotNull(session2.sessionId());
        assertFalse(session.sessionId().equals(session2.sessionId()),
                "Two sessions should have different IDs");
    }

    @Test
    void testSessionAttributeKey() {
        assertEquals("org.atmosphere.agui.session", AgUiSession.ATTRIBUTE_KEY);
    }

    @Test
    void testInheritedAttributeStorage() {
        session.setAttribute("key1", "value1");
        session.setAttribute("key2", 42);

        assertEquals("value1", session.<String>getAttribute("key1"));
        assertEquals(42, session.<Integer>getAttribute("key2"));
        assertNull(session.getAttribute("nonexistent"));
    }

    @Test
    void testInheritedTouchAndLastAccessed() {
        long beforeTouch = System.currentTimeMillis();
        session.touch();
        long afterTouch = System.currentTimeMillis();

        assertTrue(session.lastAccessedAt() >= beforeTouch);
        assertTrue(session.lastAccessedAt() <= afterTouch);
    }

    @Test
    void testInheritedExpirationCheck() throws InterruptedException {
        session.touch();
        // Session was just touched, should not be expired with a 1-second TTL
        assertFalse(session.isExpired(1000));
        // Wait 2ms to ensure time elapses, then check with 1ms TTL
        Thread.sleep(2);
        assertTrue(session.isExpired(1), "Session should be expired with 1ms TTL after 2ms sleep");
    }

    @Test
    void testInheritedPendingNotifications() {
        assertEquals(0, session.pendingCount());

        session.addPendingNotification("notification-1");
        session.addPendingNotification("notification-2");
        assertEquals(2, session.pendingCount());

        var drained = session.drainPendingNotifications();
        assertEquals(2, drained.size());
        assertEquals("notification-1", drained.get(0));
        assertEquals("notification-2", drained.get(1));
        assertEquals(0, session.pendingCount(), "Pending should be empty after drain");
    }

    @Test
    void testInheritedSubscriptions() {
        assertFalse(session.isSubscribed("topic-1"));

        session.addSubscription("topic-1");
        assertTrue(session.isSubscribed("topic-1"));

        session.removeSubscription("topic-1");
        assertFalse(session.isSubscribed("topic-1"));
    }

    @Test
    void testInheritedSubscriptionsSet() {
        session.addSubscription("topic-a");
        session.addSubscription("topic-b");

        var subs = session.subscriptions();
        assertEquals(2, subs.size());
        assertTrue(subs.contains("topic-a"));
        assertTrue(subs.contains("topic-b"));
    }

    @Test
    void testInheritedDefaultMaxPending() {
        // Default max is 100; adding 105 should only keep the last 100
        for (int i = 0; i < 105; i++) {
            session.addPendingNotification("msg-" + i);
        }
        assertEquals(100, session.pendingCount());

        var drained = session.drainPendingNotifications();
        // The first 5 should have been dropped
        assertEquals("msg-5", drained.get(0));
        assertEquals("msg-104", drained.get(drained.size() - 1));
    }

    @Test
    void testRunIdAndThreadIdCanBeSetIndependently() {
        session.setCurrentRunId("run-1");
        session.setCurrentThreadId("thread-1");

        assertEquals("run-1", session.currentRunId());
        assertEquals("thread-1", session.currentThreadId());

        // Change only run ID
        session.setCurrentRunId("run-2");
        assertEquals("run-2", session.currentRunId());
        assertEquals("thread-1", session.currentThreadId(), "ThreadId should remain unchanged");

        // Change only thread ID
        session.setCurrentThreadId("thread-2");
        assertEquals("run-2", session.currentRunId(), "RunId should remain unchanged");
        assertEquals("thread-2", session.currentThreadId());
    }

    @Test
    void testVolatileVisibility() throws Exception {
        session.setCurrentRunId("initial-run");
        session.setCurrentThreadId("initial-thread");

        var runIdHolder = new String[1];
        var threadIdHolder = new String[1];

        var thread = Thread.ofVirtual().start(() -> {
            runIdHolder[0] = session.currentRunId();
            threadIdHolder[0] = session.currentThreadId();
        });
        thread.join(5000);

        assertEquals("initial-run", runIdHolder[0],
                "RunId should be visible across threads (volatile)");
        assertEquals("initial-thread", threadIdHolder[0],
                "ThreadId should be visible across threads (volatile)");
    }
}
