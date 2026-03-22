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
import org.atmosphere.channels.ChannelType;
import org.atmosphere.channels.OutgoingMessage;

/**
 * Truncates outgoing messages that exceed the target platform's character limit.
 * <p>
 * Splits at the last paragraph or sentence boundary before the limit to avoid
 * cutting mid-word. Appends "..." when truncated.
 * <p>
 * This filter runs with default priority (100) so custom filters can run
 * before or after it.
 */
public class MessageSplittingFilter implements ChannelFilter {

    @Override
    public OutgoingMessage onOutgoing(OutgoingMessage message, ChannelType target) {
        int maxLen = target.maxLength();
        String text = message.text();

        if (text.length() <= maxLen) {
            return message;
        }

        String truncated = truncateAtBoundary(text, maxLen - 3) + "...";
        return new OutgoingMessage(message.recipientId(), truncated,
                message.replyTo(), message.parseMode());
    }

    private static String truncateAtBoundary(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }

        // Try paragraph boundary
        int lastParagraph = text.lastIndexOf("\n\n", maxLen);
        if (lastParagraph > maxLen / 2) {
            return text.substring(0, lastParagraph);
        }

        // Try sentence boundary
        int lastSentence = -1;
        for (int i = Math.min(maxLen, text.length()) - 1; i > maxLen / 2; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length() && text.charAt(i + 1) == ' ') {
                lastSentence = i + 1;
                break;
            }
        }
        if (lastSentence > 0) {
            return text.substring(0, lastSentence);
        }

        // Fall back to word boundary
        int lastSpace = text.lastIndexOf(' ', maxLen);
        if (lastSpace > maxLen / 2) {
            return text.substring(0, lastSpace);
        }

        // Hard cut
        return text.substring(0, maxLen);
    }
}
