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

import org.atmosphere.channels.ChannelFilter;
import org.atmosphere.channels.ChannelFilterChain;
import org.atmosphere.channels.ChannelType;
import org.atmosphere.channels.IncomingMessage;
import org.atmosphere.channels.OutgoingMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MessageSplittingFilter}, {@link AuditLoggingFilter},
 * and {@link ChannelFilterChain} — edge cases beyond the existing ChannelFilterTest.
 */
class ChannelFilterExtendedTest {

    // ── MessageSplittingFilter ──

    @Test
    void splittingFilter_truncatesAtParagraphBoundary() {
        var filter = new MessageSplittingFilter();
        // Two paragraphs: first 500 chars, paragraph break, then 2000 more
        var text = "A".repeat(500) + "\n\n" + "B".repeat(2000);
        var message = new OutgoingMessage("r1", text);

        var result = filter.onOutgoing(message, ChannelType.DISCORD);
        assertNotNull(result);
        assertTrue(result.text().length() <= 2000);
        assertTrue(result.text().endsWith("..."));
    }

    @Test
    void splittingFilter_truncatesAtWordBoundary() {
        var filter = new MessageSplittingFilter();
        // No sentence/paragraph boundaries, just words
        var words = "word ".repeat(500);
        var message = new OutgoingMessage("r1", words);

        var result = filter.onOutgoing(message, ChannelType.DISCORD);
        assertNotNull(result);
        assertTrue(result.text().length() <= 2000);
        assertTrue(result.text().endsWith("..."));
    }

    @Test
    void splittingFilter_hardCutWhenNoBreaks() {
        var filter = new MessageSplittingFilter();
        // One long word with no spaces
        var text = "X".repeat(3000);
        var message = new OutgoingMessage("r1", text);

        var result = filter.onOutgoing(message, ChannelType.DISCORD);
        assertNotNull(result);
        assertTrue(result.text().length() <= 2000);
        assertTrue(result.text().endsWith("..."));
    }

    @Test
    void splittingFilter_respectsAllPlatformLimits() {
        var filter = new MessageSplittingFilter();
        var longText = "Z".repeat(50_000);
        for (ChannelType type : ChannelType.values()) {
            var message = new OutgoingMessage("r1", longText);
            var result = filter.onOutgoing(message, type);
            assertNotNull(result, "Filter returned null for " + type);
            assertTrue(result.text().length() <= type.maxLength(),
                    "Exceeded limit for " + type + ": " + result.text().length());
        }
    }

    @Test
    void splittingFilter_preservesReplyToAndParseMode() {
        var filter = new MessageSplittingFilter();
        var text = "A".repeat(3000);
        var message = new OutgoingMessage("r1", text,
                Optional.of("msg-123"), Optional.of("markdown"));

        var result = filter.onOutgoing(message, ChannelType.DISCORD);
        assertNotNull(result);
        assertEquals(Optional.of("msg-123"), result.replyTo());
        assertEquals(Optional.of("markdown"), result.parseMode());
    }

    @Test
    void splittingFilter_onIncomingPassesThrough() {
        var filter = new MessageSplittingFilter();
        var msg = makeIncoming("test message");
        // Default implementation: passes through
        assertEquals(msg, filter.onIncoming(msg));
    }

    // ── AuditLoggingFilter ──

    @Test
    void auditFilter_passesIncomingThrough() {
        var filter = new AuditLoggingFilter();
        var msg = makeIncoming("hello");
        var result = filter.onIncoming(msg);
        assertNotNull(result);
        assertEquals("hello", result.text());
    }

    @Test
    void auditFilter_passesOutgoingThrough() {
        var filter = new AuditLoggingFilter();
        var msg = new OutgoingMessage("r1", "response");
        var result = filter.onOutgoing(msg, ChannelType.SLACK);
        assertNotNull(result);
        assertEquals("response", result.text());
    }

    @Test
    void auditFilter_orderIs10() {
        assertEquals(10, new AuditLoggingFilter().order());
    }

    @Test
    void auditFilter_handlesLongText() {
        var filter = new AuditLoggingFilter();
        var longText = "L".repeat(500);
        var msg = makeIncoming(longText);
        // Should not throw — truncates to 200 chars for logging
        var result = filter.onIncoming(msg);
        assertNotNull(result);
        assertEquals(longText, result.text());
    }

    // ── ChannelFilterChain additional tests ──

    @Test
    void emptyChain_passesIncomingThrough() {
        var chain = new ChannelFilterChain(List.of());
        var msg = makeIncoming("hello");
        var result = chain.filterIncoming(msg);
        assertEquals(msg, result);
    }

    @Test
    void emptyChain_passesOutgoingThrough() {
        var chain = new ChannelFilterChain(List.of());
        var msg = new OutgoingMessage("r1", "hello");
        var result = chain.filterOutgoing(msg, ChannelType.TELEGRAM);
        assertEquals(msg, result);
    }

    @Test
    void chain_outboundBlockingStopsChain() {
        ChannelFilter blocker = new ChannelFilter() {
            @Override
            public OutgoingMessage onOutgoing(OutgoingMessage m, ChannelType target) {
                return null;
            }

            @Override
            public int order() {
                return 1;
            }
        };
        var chain = new ChannelFilterChain(List.of(blocker));
        var msg = new OutgoingMessage("r1", "blocked");
        assertNull(chain.filterOutgoing(msg, ChannelType.SLACK));
    }

    @Test
    void chain_multipleOutboundTransforms() {
        ChannelFilter prefix = new ChannelFilter() {
            @Override
            public OutgoingMessage onOutgoing(OutgoingMessage m, ChannelType target) {
                return new OutgoingMessage(m.recipientId(), "[" + target.id() + "] " + m.text(),
                        m.replyTo(), m.parseMode());
            }

            @Override
            public int order() {
                return 1;
            }
        };
        ChannelFilter splitter = new MessageSplittingFilter();

        var chain = new ChannelFilterChain(List.of(splitter, prefix));
        var msg = new OutgoingMessage("r1", "hello");
        var result = chain.filterOutgoing(msg, ChannelType.TELEGRAM);
        assertNotNull(result);
        assertTrue(result.text().startsWith("[telegram] "));
    }

    // ── helpers ──

    private static IncomingMessage makeIncoming(String text) {
        return new IncomingMessage(ChannelType.TELEGRAM, "sender-1",
                Optional.empty(), text, "conv-1", "msg-1", Instant.now());
    }
}
