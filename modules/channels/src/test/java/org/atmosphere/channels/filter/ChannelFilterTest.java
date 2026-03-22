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
package org.atmosphere.channels.filter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.atmosphere.channels.ChannelFilter;
import org.atmosphere.channels.ChannelFilterChain;
import org.atmosphere.channels.ChannelType;
import org.atmosphere.channels.IncomingMessage;
import org.atmosphere.channels.OutgoingMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelFilterTest {

    @Test
    void messageSplittingFilter_truncatesLongMessages() {
        var filter = new MessageSplittingFilter();
        var longText = "A".repeat(5000);
        var message = new OutgoingMessage("chat-1", longText);

        var result = filter.onOutgoing(message, ChannelType.DISCORD); // 2000 char limit

        assertNotNull(result);
        assertTrue(result.text().length() <= 2000);
        assertTrue(result.text().endsWith("..."));
    }

    @Test
    void messageSplittingFilter_passesShortMessages() {
        var filter = new MessageSplittingFilter();
        var message = new OutgoingMessage("chat-1", "Short message");

        var result = filter.onOutgoing(message, ChannelType.SLACK); // 40K limit

        assertNotNull(result);
        assertEquals("Short message", result.text());
    }

    @Test
    void messageSplittingFilter_splitsAtSentenceBoundary() {
        var filter = new MessageSplittingFilter();
        var text = "First sentence. Second sentence. " + "X".repeat(2000);
        var message = new OutgoingMessage("chat-1", text);

        var result = filter.onOutgoing(message, ChannelType.DISCORD);

        assertNotNull(result);
        assertTrue(result.text().contains("First sentence."));
    }

    @Test
    void filterChain_runsInOrder() {
        var sb = new StringBuilder();

        ChannelFilter first = new ChannelFilter() {
            @Override public IncomingMessage onIncoming(IncomingMessage m) {
                sb.append("A");
                return m;
            }
            @Override public int order() { return 1; }
        };

        ChannelFilter second = new ChannelFilter() {
            @Override public IncomingMessage onIncoming(IncomingMessage m) {
                sb.append("B");
                return m;
            }
            @Override public int order() { return 2; }
        };

        var chain = new ChannelFilterChain(List.of(second, first)); // Reversed on purpose
        var msg = new IncomingMessage(ChannelType.TELEGRAM, "u1", Optional.empty(),
                "test", "c1", "m1", Instant.now());

        chain.filterIncoming(msg);
        assertEquals("AB", sb.toString()); // Should run A before B
    }

    @Test
    void filterChain_blockingFilterStopsChain() {
        ChannelFilter blocker = new ChannelFilter() {
            @Override public IncomingMessage onIncoming(IncomingMessage m) {
                return null; // Block
            }
        };

        ChannelFilter after = new ChannelFilter() {
            @Override public IncomingMessage onIncoming(IncomingMessage m) {
                fail("Should not reach this filter");
                return m;
            }
            @Override public int order() { return 200; }
        };

        var chain = new ChannelFilterChain(List.of(blocker, after));
        var msg = new IncomingMessage(ChannelType.SLACK, "u1", Optional.empty(),
                "spam", "c1", "m1", Instant.now());

        assertNull(chain.filterIncoming(msg));
    }

    @Test
    void filterChain_outboundFilterTransforms() {
        ChannelFilter uppercaser = new ChannelFilter() {
            @Override public OutgoingMessage onOutgoing(OutgoingMessage m, ChannelType target) {
                return new OutgoingMessage(m.recipientId(), m.text().toUpperCase(),
                        m.replyTo(), m.parseMode());
            }
        };

        var chain = new ChannelFilterChain(List.of(uppercaser));
        var msg = new OutgoingMessage("chat-1", "hello world");

        var result = chain.filterOutgoing(msg, ChannelType.TELEGRAM);

        assertNotNull(result);
        assertEquals("HELLO WORLD", result.text());
    }
}
