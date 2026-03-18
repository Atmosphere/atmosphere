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
package org.atmosphere.a2a;

import org.atmosphere.a2a.runtime.A2aSession;
import org.atmosphere.protocol.ProtocolSession;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aSessionTest {

    @Test
    void testActiveTaskTracking() {
        var session = new A2aSession();

        assertTrue(session.activeTaskIds().isEmpty());

        session.trackTask("task-1");
        session.trackTask("task-2");
        session.trackTask("task-3");

        assertEquals(3, session.activeTaskIds().size());
        assertTrue(session.activeTaskIds().contains("task-1"));
        assertTrue(session.activeTaskIds().contains("task-2"));
        assertTrue(session.activeTaskIds().contains("task-3"));

        session.untrackTask("task-2");

        assertEquals(2, session.activeTaskIds().size());
        assertFalse(session.activeTaskIds().contains("task-2"));
        assertTrue(session.activeTaskIds().contains("task-1"));
        assertTrue(session.activeTaskIds().contains("task-3"));
    }

    @Test
    void testActiveTaskIdsReturnsUnmodifiableSet() {
        var session = new A2aSession();
        session.trackTask("task-1");

        Set<String> ids = session.activeTaskIds();
        try {
            ids.add("task-illegal");
            // If we get here, the set is not truly unmodifiable
            assertFalse(ids.contains("task-illegal"),
                    "activeTaskIds() should return an unmodifiable set");
        } catch (UnsupportedOperationException e) {
            // Expected behavior
        }
    }

    @Test
    void testTrackSameTaskTwiceIsIdempotent() {
        var session = new A2aSession();
        session.trackTask("task-1");
        session.trackTask("task-1");

        assertEquals(1, session.activeTaskIds().size());
    }

    @Test
    void testUntrackNonExistentTaskIsNoOp() {
        var session = new A2aSession();
        session.trackTask("task-1");

        // Should not throw
        session.untrackTask("task-nonexistent");

        assertEquals(1, session.activeTaskIds().size());
    }

    @Test
    void testSessionExtendsProtocolSession() {
        var session = new A2aSession();

        // Verify it extends ProtocolSession
        assertInstanceOf(ProtocolSession.class, session);

        // Verify session ID is generated
        assertNotNull(session.sessionId());
        assertFalse(session.sessionId().isEmpty());

        // Verify attributes work (from ProtocolSession)
        session.setAttribute("key1", "value1");
        assertEquals("value1", session.<String>getAttribute("key1"));

        session.setAttribute("key2", 42);
        assertEquals(42, session.<Integer>getAttribute("key2"));
    }

    @Test
    void testSessionTtl() throws Exception {
        var session = new A2aSession();

        // Session should not be expired immediately with a large TTL
        assertFalse(session.isExpired(ProtocolSession.DEFAULT_TTL_MS));

        // Touch updates last accessed time
        session.touch();
        assertFalse(session.isExpired(ProtocolSession.DEFAULT_TTL_MS));

        // Wait a small amount so that elapsed time > 1ms, then check with TTL=1
        Thread.sleep(5);
        assertTrue(session.isExpired(1),
                "Session should be expired when TTL is less than elapsed time");
    }

    @Test
    void testSessionPendingNotifications() {
        var session = new A2aSession();

        assertEquals(0, session.pendingCount());

        session.addPendingNotification("notification-1");
        session.addPendingNotification("notification-2");

        assertEquals(2, session.pendingCount());

        var drained = session.drainPendingNotifications();
        assertEquals(2, drained.size());
        assertEquals("notification-1", drained.get(0));
        assertEquals("notification-2", drained.get(1));

        // After drain, queue should be empty
        assertEquals(0, session.pendingCount());
    }

    @Test
    void testSessionMaxPendingNotifications() {
        var maxPending = 3;
        var session = new A2aSession(maxPending);

        session.addPendingNotification("n1");
        session.addPendingNotification("n2");
        session.addPendingNotification("n3");
        session.addPendingNotification("n4"); // should evict n1

        assertEquals(3, session.pendingCount());

        var drained = session.drainPendingNotifications();
        assertEquals(3, drained.size());
        assertEquals("n2", drained.get(0)); // n1 was evicted
        assertEquals("n3", drained.get(1));
        assertEquals("n4", drained.get(2));
    }

    @Test
    void testSessionSubscriptions() {
        var session = new A2aSession();

        assertFalse(session.isSubscribed("topic-1"));

        session.addSubscription("topic-1");
        session.addSubscription("topic-2");

        assertTrue(session.isSubscribed("topic-1"));
        assertTrue(session.isSubscribed("topic-2"));
        assertFalse(session.isSubscribed("topic-3"));

        session.removeSubscription("topic-1");
        assertFalse(session.isSubscribed("topic-1"));
        assertTrue(session.isSubscribed("topic-2"));

        var subs = session.subscriptions();
        assertEquals(1, subs.size());
        assertTrue(subs.contains("topic-2"));
    }

    @Test
    void testSessionConstants() {
        assertEquals("org.atmosphere.a2a.session", A2aSession.ATTRIBUTE_KEY);
        assertEquals("A2a-Session-Id", A2aSession.SESSION_ID_HEADER);
    }

    @Test
    void testTwoSessionsHaveUniqueIds() {
        var session1 = new A2aSession();
        var session2 = new A2aSession();

        assertNotNull(session1.sessionId());
        assertNotNull(session2.sessionId());
        assertFalse(session1.sessionId().equals(session2.sessionId()),
                "Two sessions should have different IDs");
    }

    @Test
    void testActiveTasksIsolatedFromOtherSessions() {
        var session1 = new A2aSession();
        var session2 = new A2aSession();

        session1.trackTask("task-A");
        session2.trackTask("task-B");

        assertTrue(session1.activeTaskIds().contains("task-A"));
        assertFalse(session1.activeTaskIds().contains("task-B"));

        assertTrue(session2.activeTaskIds().contains("task-B"));
        assertFalse(session2.activeTaskIds().contains("task-A"));
    }
}
