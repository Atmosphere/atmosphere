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
package org.atmosphere.channels.discord;

import org.atmosphere.channels.ChannelType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DiscordChannel} covering channel metadata and
 * gateway-based receive behavior (which returns empty since messages
 * arrive via the Gateway, not webhooks).
 */
class DiscordChannelTest {

    private final DiscordChannel channel = new DiscordChannel(
            "test-bot-token",
            new tools.jackson.databind.ObjectMapper(),
            msg -> { });

    @Test
    void channelTypeIsDiscord() {
        assertEquals(ChannelType.DISCORD, channel.channelType());
    }

    @Test
    void webhookPathIsCorrect() {
        assertEquals("/webhook/discord", channel.webhookPath());
    }

    @Test
    void maxMessageLengthIs2000() {
        assertEquals(2000, channel.maxMessageLength());
    }

    @Test
    void verifySignatureDoesNotThrow() {
        // Gateway-based — no signature verification needed
        channel.verifySignature(Map.of(), new byte[0]);
    }

    @Test
    void receiveReturnsEmptyListBecauseGatewayHandlesMessages() {
        List<?> messages = channel.receive(Map.of(), new byte[0]);
        assertTrue(messages.isEmpty());
    }
}
