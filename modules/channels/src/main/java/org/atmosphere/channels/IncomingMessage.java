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

import java.time.Instant;
import java.util.Optional;

/**
 * A message received from an external messaging platform.
 *
 * @param channelType     the platform this message came from
 * @param senderId        platform-specific sender identifier
 * @param senderName      human-readable sender name, if available
 * @param text            message text content
 * @param conversationId  platform-specific conversation/chat identifier
 * @param messageId       platform-specific message identifier
 * @param timestamp       when the message was received
 */
public record IncomingMessage(
        ChannelType channelType,
        String senderId,
        Optional<String> senderName,
        String text,
        String conversationId,
        String messageId,
        Instant timestamp
) {
}
