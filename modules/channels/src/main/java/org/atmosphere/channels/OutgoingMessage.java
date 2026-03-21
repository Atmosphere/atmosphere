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
package org.atmosphere.channels;

import java.util.Optional;

/**
 * A message to be sent to an external messaging platform.
 *
 * @param recipientId  platform-specific recipient (chat ID, channel ID)
 * @param text         message text (markdown supported — rendered per platform)
 * @param replyTo      optional message ID to reply to (for threading)
 * @param parseMode    optional platform-specific parse mode override
 */
public record OutgoingMessage(
        String recipientId,
        String text,
        Optional<String> replyTo,
        Optional<String> parseMode
) {
    public OutgoingMessage(String recipientId, String text) {
        this(recipientId, text, Optional.empty(), Optional.empty());
    }
}
