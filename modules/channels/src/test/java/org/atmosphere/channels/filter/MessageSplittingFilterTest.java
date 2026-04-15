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

import org.atmosphere.channels.ChannelType;
import org.atmosphere.channels.OutgoingMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MessageSplittingFilterTest {

    private final MessageSplittingFilter filter = new MessageSplittingFilter();

    @Test
    void shortMessagePassesThroughUnchanged() {
        var msg = new OutgoingMessage("chat1", "Hello world");
        var result = filter.onOutgoing(msg, ChannelType.TELEGRAM);
        assertSame(msg, result);
    }

    @Test
    void messageExactlyAtLimitPassesThrough() {
        var text = "x".repeat(ChannelType.TELEGRAM.maxLength());
        var msg = new OutgoingMessage("chat1", text);
        var result = filter.onOutgoing(msg, ChannelType.TELEGRAM);
        assertSame(msg, result);
    }

    @Test
    void messageExceedingLimitIsTruncatedWithEllipsis() {
        var text = "x".repeat(ChannelType.DISCORD.maxLength() + 100);
        var msg = new OutgoingMessage("chat1", text);
        var result = filter.onOutgoing(msg, ChannelType.DISCORD);
        assertEquals(ChannelType.DISCORD.maxLength(), result.text().length());
        assertEquals("...", result.text().substring(result.text().length() - 3));
    }

    @Test
    void truncatesAtParagraphBoundary() {
        // Build text with a paragraph break in the middle
        var firstParagraph = "A".repeat(1200);
        var secondParagraph = "B".repeat(1200);
        var text = firstParagraph + "\n\n" + secondParagraph;
        var msg = new OutgoingMessage("chat1", text);
        // Discord max is 2000; text is 2402 chars
        var result = filter.onOutgoing(msg, ChannelType.DISCORD);
        // Should truncate at the paragraph boundary (after firstParagraph)
        assertEquals(firstParagraph + "...", result.text());
    }

    @Test
    void truncatesAtSentenceBoundaryWhenNoParagraphBreak() {
        // Build text with sentences but no paragraph break in upper half
        var sentence1 = "A".repeat(1500) + ". ";
        var sentence2 = "B".repeat(600);
        var text = sentence1 + sentence2;
        var msg = new OutgoingMessage("chat1", text);
        // Discord max is 2000; text is 2102 chars
        var result = filter.onOutgoing(msg, ChannelType.DISCORD);
        // Should truncate at the sentence boundary (period + space)
        assertEquals(sentence1.substring(0, sentence1.length() - 1) + "...", result.text());
    }

    @Test
    void truncatesAtWordBoundaryWhenNoSentenceBoundary() {
        // Build a single long sentence with spaces but no sentence-ending punctuation
        var builder = new StringBuilder();
        while (builder.length() < 2200) {
            builder.append("word ");
        }
        var text = builder.toString();
        var msg = new OutgoingMessage("chat1", text);
        var result = filter.onOutgoing(msg, ChannelType.DISCORD);
        // Truncated text should end with "..."
        assertEquals("...", result.text().substring(result.text().length() - 3));
        // The cut should be at a space boundary (no partial words)
        var beforeEllipsis = result.text().substring(0, result.text().length() - 3);
        // "word word word..." => cuts at a space, so beforeEllipsis is "word word...word"
        // The original text after beforeEllipsis should start with a space
        assertEquals(' ', text.charAt(beforeEllipsis.length()));
    }

    @Test
    void hardCutWhenNoBreakpoints() {
        // Single continuous string with no spaces, sentences, or paragraphs
        var text = "x".repeat(2500);
        var msg = new OutgoingMessage("chat1", text);
        var result = filter.onOutgoing(msg, ChannelType.DISCORD);
        assertEquals(ChannelType.DISCORD.maxLength(), result.text().length());
        assertEquals("...", result.text().substring(result.text().length() - 3));
    }

    @Test
    void preservesRecipientAndOptionalFields() {
        var text = "x".repeat(2500);
        var msg = new OutgoingMessage("chat42", text,
                Optional.of("reply-id"), Optional.of("markdown"));
        var result = filter.onOutgoing(msg, ChannelType.DISCORD);
        assertEquals("chat42", result.recipientId());
        assertEquals(Optional.of("reply-id"), result.replyTo());
        assertEquals(Optional.of("markdown"), result.parseMode());
    }

    @Test
    void respectsDifferentChannelLimits() {
        // Use text longer than Discord (2000) but shorter than Telegram (4096)
        var text = "x".repeat(3000);
        var msg = new OutgoingMessage("chat1", text);

        var discordResult = filter.onOutgoing(msg, ChannelType.DISCORD);
        assertEquals(ChannelType.DISCORD.maxLength(), discordResult.text().length());

        // Same text passes through for Telegram (3000 < 4096)
        var telegramResult = filter.onOutgoing(msg, ChannelType.TELEGRAM);
        assertEquals(3000, telegramResult.text().length());

        // Use text longer than Telegram (4096) to test Telegram truncation
        var longText = "x".repeat(5000);
        var longMsg = new OutgoingMessage("chat1", longText);
        var telegramTruncated = filter.onOutgoing(longMsg, ChannelType.TELEGRAM);
        assertEquals(ChannelType.TELEGRAM.maxLength(), telegramTruncated.text().length());
    }

    @Test
    void incomingMessagePassesThroughByDefault() {
        // ChannelFilter default onIncoming returns message unchanged
        assertEquals(100, filter.order());
    }
}
