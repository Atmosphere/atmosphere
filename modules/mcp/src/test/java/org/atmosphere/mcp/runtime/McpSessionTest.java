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
package org.atmosphere.mcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpSession} lifecycle, pending notification queue,
 * resource subscriptions, and session TTL management.
 */
class McpSessionTest {

    @Test
    void newSessionHasUniqueId() {
        var session1 = new McpSession();
        var session2 = new McpSession();
        assertNotNull(session1.sessionId());
        assertNotNull(session2.sessionId());
        assertFalse(session1.sessionId().equals(session2.sessionId()));
    }

    @Test
    void newSessionIsNotInitialized() {
        var session = new McpSession();
        assertFalse(session.isInitialized());
    }

    @Test
    void markInitializedSetsFlag() {
        var session = new McpSession();
        session.markInitialized();
        assertTrue(session.isInitialized());
    }

    @Test
    void setClientInfoStoresValues() {
        var session = new McpSession();
        var caps = Map.<String, Object>of("roots", true);
        session.setClientInfo("TestClient", "1.0", caps);

        assertEquals("TestClient", session.clientName());
        assertEquals("1.0", session.clientVersion());
        assertEquals(caps, session.clientCapabilities());
    }

    @Test
    void setClientInfoWithNullCapabilities() {
        var session = new McpSession();
        session.setClientInfo("Client", "2.0", null);

        assertEquals(Map.of(), session.clientCapabilities());
    }

    @Test
    void attributeStorage() {
        var session = new McpSession();
        session.setAttribute("key1", "value1");
        session.setAttribute("count", 42);

        String val = session.getAttribute("key1");
        assertEquals("value1", val);

        Integer count = session.getAttribute("count");
        assertEquals(42, count);
    }

    @Test
    void attributeReturnsNullForMissingKey() {
        var session = new McpSession();
        Object val = session.getAttribute("nonexistent");
        assertNull(val);
    }

    // ── Session TTL ─────────────────────────────────────────────────────

    @Test
    void touchUpdatesLastAccessedAt() throws Exception {
        var session = new McpSession();
        long before = session.lastAccessedAt();
        Thread.sleep(20);
        session.touch();
        assertTrue(session.lastAccessedAt() >= before + 15);
    }

    @Test
    void isExpiredReturnsFalseForFreshSession() {
        var session = new McpSession();
        assertFalse(session.isExpired(McpSession.DEFAULT_TTL_MS));
    }

    @Test
    void isExpiredReturnsTrueForZeroTtl() throws Exception {
        var session = new McpSession();
        Thread.sleep(5);
        assertTrue(session.isExpired(0));
    }

    // ── Pending notification queue ──────────────────────────────────────

    @Test
    void addAndDrainPendingNotifications() {
        var session = new McpSession();
        session.addPendingNotification("msg1");
        session.addPendingNotification("msg2");

        assertEquals(2, session.pendingCount());

        var drained = session.drainPendingNotifications();
        assertEquals(2, drained.size());
        assertEquals("msg1", drained.get(0));
        assertEquals("msg2", drained.get(1));

        assertEquals(0, session.pendingCount());
    }

    @Test
    void drainReturnsEmptyListWhenNoPending() {
        var session = new McpSession();
        var drained = session.drainPendingNotifications();
        assertNotNull(drained);
        assertTrue(drained.isEmpty());
    }

    @Test
    void pendingQueueDropsOldestWhenFull() {
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

    // ── Resource subscriptions ──────────────────────────────────────────

    @Test
    void addAndCheckSubscription() {
        var session = new McpSession();
        assertFalse(session.isSubscribed("file:///data.json"));

        session.addSubscription("file:///data.json");
        assertTrue(session.isSubscribed("file:///data.json"));
    }

    @Test
    void removeSubscription() {
        var session = new McpSession();
        session.addSubscription("file:///data.json");
        session.removeSubscription("file:///data.json");
        assertFalse(session.isSubscribed("file:///data.json"));
    }

    @Test
    void subscriptionsReturnsUnmodifiableCopy() {
        var session = new McpSession();
        session.addSubscription("uri1");
        session.addSubscription("uri2");

        var subs = session.subscriptions();
        assertEquals(2, subs.size());
        assertTrue(subs.contains("uri1"));
        assertTrue(subs.contains("uri2"));
    }

    @Test
    void subscriptionsIsDefensiveCopy() {
        var session = new McpSession();
        session.addSubscription("uri1");

        var subs = session.subscriptions();
        session.addSubscription("uri2");

        // The returned set should be a snapshot, not affected by later additions
        assertEquals(1, subs.size());
    }

    // ── Constants ───────────────────────────────────────────────────────

    @Test
    void constantsHaveExpectedValues() {
        assertEquals("org.atmosphere.mcp.session", McpSession.ATTRIBUTE_KEY);
        assertEquals("Mcp-Session-Id", McpSession.SESSION_ID_HEADER);
        assertEquals(100, McpSession.DEFAULT_MAX_PENDING);
        assertEquals(30 * 60 * 1000L, McpSession.DEFAULT_TTL_MS);
    }
}
