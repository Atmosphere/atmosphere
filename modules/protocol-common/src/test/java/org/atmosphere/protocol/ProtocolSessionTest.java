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
package org.atmosphere.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSessionTest {

    @Test
    void sessionIdIsGenerated() {
        var session = new ProtocolSession();
        assertNotNull(session.sessionId());
        assertFalse(session.sessionId().isBlank());
    }

    @Test
    void attributesAreStoredAndRetrieved() {
        var session = new ProtocolSession();
        session.setAttribute("key", "value");
        assertEquals("value", session.<String>getAttribute("key"));
        assertNull(session.getAttribute("missing"));
    }

    @Test
    void touchUpdatesLastAccessedAt() throws InterruptedException {
        var session = new ProtocolSession();
        long before = session.lastAccessedAt();
        Thread.sleep(10);
        session.touch();
        assertTrue(session.lastAccessedAt() >= before);
    }

    @Test
    void expiredCheckWorksCorrectly() throws InterruptedException {
        var session = new ProtocolSession();
        assertFalse(session.isExpired(60_000L));
        // Wait briefly then check with a very short TTL
        Thread.sleep(5);
        assertTrue(session.isExpired(1L));
    }

    @Test
    void pendingNotificationsAreQueuedAndDrained() {
        var session = new ProtocolSession(3);
        session.addPendingNotification("a");
        session.addPendingNotification("b");
        session.addPendingNotification("c");
        assertEquals(3, session.pendingCount());

        // Overflow drops oldest
        session.addPendingNotification("d");
        assertEquals(3, session.pendingCount());

        var drained = session.drainPendingNotifications();
        assertEquals(3, drained.size());
        assertEquals("b", drained.get(0));
        assertEquals("c", drained.get(1));
        assertEquals("d", drained.get(2));
        assertEquals(0, session.pendingCount());
    }

    @Test
    void subscriptionsAreTracked() {
        var session = new ProtocolSession();
        session.addSubscription("test://resource");
        assertTrue(session.isSubscribed("test://resource"));
        assertFalse(session.isSubscribed("other://resource"));

        session.removeSubscription("test://resource");
        assertFalse(session.isSubscribed("test://resource"));
        assertTrue(session.subscriptions().isEmpty());
    }
}
