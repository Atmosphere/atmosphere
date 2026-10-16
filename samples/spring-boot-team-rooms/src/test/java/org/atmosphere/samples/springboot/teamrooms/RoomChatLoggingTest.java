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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A secret posted to a room must not reach the server log. {@link RedactingFilter} cleans
 * the broadcast on its way to the wire, but {@link RoomChat#onMessage} runs before the
 * filter, so whatever it logs is the raw client text. The 2026-10-16 sweep found the log
 * line {@code [general] Alice: deploy with Bearer sweepTok3n... please} at INFO.
 */
class RoomChatLoggingTest {

    private static final String TOKEN = "sweepTok3n.ABCdef123456xyz";

    private final Logger roomChatLogger = (Logger) LoggerFactory.getLogger(RoomChat.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureRoomChatLog() {
        previousLevel = roomChatLogger.getLevel();
        roomChatLogger.setLevel(Level.TRACE);
        appender.start();
        roomChatLogger.addAppender(appender);
    }

    @AfterEach
    void releaseRoomChatLog() {
        roomChatLogger.detachAppender(appender);
        appender.stop();
        roomChatLogger.setLevel(previousLevel);
    }

    @Test
    void aBearerTokenInAPostNeverReachesTheLog() throws ReflectiveOperationException {
        var chat = new RoomChat();
        var room = RoomChat.class.getDeclaredField("room");
        room.setAccessible(true);
        room.set(chat, "general");

        var posted = new Message(null, "Alice", "deploy with Bearer " + TOKEN + " please",
                Instant.parse("2026-10-16T14:00:00Z"));
        var stamped = chat.onMessage(posted);

        // The handler still returns the text untouched: redaction is the filter's job.
        assertEquals(posted.text(), stamped.text());
        assertEquals("general", stamped.room());

        assertFalse(appender.list.isEmpty(), "onMessage is expected to log the post");
        for (var event : appender.list) {
            assertFalse(event.getFormattedMessage().contains(TOKEN),
                    "the bearer token reached the log: " + event.getFormattedMessage());
        }
        assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("general")),
                "the log line should still name the room");
    }
}
