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
import org.atmosphere.channels.IncomingMessage;
import org.atmosphere.channels.OutgoingMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link AuditLoggingFilter} verifying pass-through behavior
 * and the configured order.
 */
class AuditLoggingFilterTest {

    private final AuditLoggingFilter filter = new AuditLoggingFilter();

    @Test
    void orderReturns10() {
        assertEquals(10, filter.order());
    }

    @Test
    void onIncomingReturnsMessageUnchanged() {
        var msg = new IncomingMessage(
                ChannelType.SLACK, "U123", Optional.of("Alice"),
                "Hello world", "C456", "M789", Instant.now());
        var result = filter.onIncoming(msg);
        assertSame(msg, result);
    }

    @Test
    void onIncomingPassesThroughLongText() {
        var longText = "x".repeat(500);
        var msg = new IncomingMessage(
                ChannelType.TELEGRAM, "U1", Optional.empty(),
                longText, "C1", "M1", Instant.now());
        var result = filter.onIncoming(msg);
        assertSame(msg, result);
        assertEquals(500, result.text().length());
    }

    @Test
    void onIncomingHandlesExactly200Chars() {
        var text200 = "a".repeat(200);
        var msg = new IncomingMessage(
                ChannelType.DISCORD, "U1", Optional.empty(),
                text200, "C1", "M1", Instant.now());
        var result = filter.onIncoming(msg);
        assertSame(msg, result);
    }

    @Test
    void onOutgoingReturnsMessageUnchanged() {
        var msg = new OutgoingMessage("R123", "Reply text");
        var result = filter.onOutgoing(msg, ChannelType.SLACK);
        assertSame(msg, result);
    }

    @Test
    void onOutgoingWorksWithAllChannelTypes() {
        var msg = new OutgoingMessage("R1", "text");
        for (var ct : ChannelType.values()) {
            var result = filter.onOutgoing(msg, ct);
            assertSame(msg, result, "Filter should pass through for " + ct);
        }
    }

    @Test
    void onIncomingWithShortText() {
        var msg = new IncomingMessage(
                ChannelType.WHATSAPP, "U1", Optional.of("Bob"),
                "Hi", "C1", "M1", Instant.now());
        var result = filter.onIncoming(msg);
        assertSame(msg, result);
    }
}
