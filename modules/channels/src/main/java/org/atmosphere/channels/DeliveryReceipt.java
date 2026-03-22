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
 * Confirmation that a message was delivered to an external platform.
 *
 * @param channelMessageId  platform-specific message ID (for edits/replies)
 * @param status            delivery status
 * @param timestamp         when delivery was confirmed
 */
public record DeliveryReceipt(
        Optional<String> channelMessageId,
        DeliveryStatus status,
        Instant timestamp
) {
    public enum DeliveryStatus {
        SENT, DELIVERED, READ, FAILED
    }
}
