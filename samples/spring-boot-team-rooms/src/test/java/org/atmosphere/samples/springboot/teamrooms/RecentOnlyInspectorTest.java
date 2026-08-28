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
package org.atmosphere.samples.springboot.teamrooms;

import java.time.Instant;

import org.atmosphere.cache.BroadcastMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecentOnlyInspector} decides what is worth replaying. These pin the two exclusions
 * that are easy to regress into "cache everything": blank noise, and announcements that
 * {@link Announcements} already fanned out to every room.
 */
class RecentOnlyInspectorTest {

    private final RecentOnlyInspector inspector = new RecentOnlyInspector();

    private boolean inspect(Message m) {
        return inspector.inspect(new BroadcastMessage("id-1", m));
    }

    private static Message chat(String room, String text) {
        return new Message(room, "alice", text, Instant.parse("2026-08-28T10:00:00Z"));
    }

    @Test
    void keepsOrdinaryRoomChat() {
        assertTrue(inspect(chat("build", "ship it")));
    }

    @Test
    void dropsBlankLines() {
        assertFalse(inspect(chat("build", "   ")), "whitespace-only lines are replay noise");
        assertFalse(inspect(chat("build", "")), "empty lines are replay noise");
    }

    @Test
    void dropsNullText() {
        assertFalse(inspect(chat("build", null)));
    }

    @Test
    void dropsAnnouncementsBecauseTheyAreAlreadyFannedOut() {
        assertFalse(inspect(chat("*", "all hands at 3")),
                "@DeliverTo(ALL) already delivered this to every room; caching it per-room duplicates it");
    }

    @Test
    void keepsNonChatPayloads() {
        assertTrue(inspector.inspect(new BroadcastMessage("id-2", "raw string")),
                "the inspector must not swallow payloads it does not understand");
    }
}
