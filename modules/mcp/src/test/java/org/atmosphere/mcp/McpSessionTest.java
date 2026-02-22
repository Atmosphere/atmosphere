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

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpSession} — TTL, pending notification queue, attributes.
 */
public class McpSessionTest {

    @Test
    public void testSessionIdIsUnique() {
        var s1 = new McpSession();
        var s2 = new McpSession();
        assertNotEquals(s2.sessionId(), s1.sessionId());
        assertNotNull(s1.sessionId());
    }

    @Test
    public void testInitialization() {
        var session = new McpSession();
        assertFalse(session.isInitialized());
        session.markInitialized();
        assertTrue(session.isInitialized());
    }

    @Test
    public void testClientInfo() {
        var session = new McpSession();
        assertNull(session.clientName());
        assertNull(session.clientVersion());
        assertTrue(session.clientCapabilities().isEmpty());

        session.setClientInfo("TestClient", "1.0", Map.of("sampling", Map.of()));
        assertEquals("TestClient", session.clientName());
        assertEquals("1.0", session.clientVersion());
        assertTrue(session.clientCapabilities().containsKey("sampling"));
    }

    @Test
    public void testClientInfoNullCapabilities() {
        var session = new McpSession();
        session.setClientInfo("C", "1", null);
        assertNotNull(session.clientCapabilities());
        assertTrue(session.clientCapabilities().isEmpty());
    }

    @Test
    public void testAttributes() {
        var session = new McpSession();
        session.setAttribute("key1", "value1");
        assertEquals("value1", session.<String>getAttribute("key1"));
        assertNull(session.getAttribute("nonexistent"));
    }

    // ── TTL tests ───────────────────────────────────────────────────────

    @Test
    public void testTouch() throws InterruptedException {
        var session = new McpSession();
        long before = session.lastAccessedAt();
        Thread.sleep(10);
        session.touch();
        assertTrue(session.lastAccessedAt() > before);
    }

    @Test
    public void testIsExpiredFalseWhenFresh() {
        var session = new McpSession();
        assertFalse(session.isExpired(60_000));
    }

    @Test
    public void testIsExpiredTrueWithZeroTtl() throws InterruptedException {
        var session = new McpSession();
        Thread.sleep(5);
        assertTrue(session.isExpired(1));
    }

    // ── Pending notification queue ──────────────────────────────────────

    @Test
    public void testPendingNotificationsEmpty() {
        var session = new McpSession();
        assertEquals(0, session.pendingCount());
        assertTrue(session.drainPendingNotifications().isEmpty());
    }

    @Test
    public void testAddAndDrainPendingNotifications() {
        var session = new McpSession();
        session.addPendingNotification("msg1");
        session.addPendingNotification("msg2");
        session.addPendingNotification("msg3");
        assertEquals(3, session.pendingCount());

        var drained = session.drainPendingNotifications();
        assertEquals(3, drained.size());
        assertEquals("msg1", drained.get(0));
        assertEquals("msg2", drained.get(1));
        assertEquals("msg3", drained.get(2));
        assertEquals(0, session.pendingCount());
    }

    @Test
    public void testPendingNotificationsDropsOldest() {
        var session = new McpSession(3);
        session.addPendingNotification("a");
        session.addPendingNotification("b");
        session.addPendingNotification("c");
        session.addPendingNotification("d");

        assertEquals(3, session.pendingCount());
        var drained = session.drainPendingNotifications();
        assertEquals("b", drained.get(0));
        assertEquals("c", drained.get(1));
        assertEquals("d", drained.get(2));
    }

    @Test
    public void testDrainClearsQueue() {
        var session = new McpSession();
        session.addPendingNotification("x");
        session.drainPendingNotifications();
        assertEquals(0, session.pendingCount());
        session.addPendingNotification("y");
        assertEquals(1, session.pendingCount());
    }

    @Test
    public void testDefaultMaxPending() {
        var session = new McpSession();
        for (int i = 0; i < 150; i++) {
            session.addPendingNotification("msg-" + i);
        }
        assertEquals(McpSession.DEFAULT_MAX_PENDING, session.pendingCount());
        var drained = session.drainPendingNotifications();
        // Should have the last 100 messages (50-149)
        assertEquals("msg-50", drained.get(0));
        assertEquals("msg-149", drained.get(99));
    }
}
