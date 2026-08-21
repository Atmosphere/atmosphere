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

import org.atmosphere.channels.ChannelException;
import org.atmosphere.channels.ChannelType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link DiscordChannel}. Discord receives via the Gateway, so
 * the channel exposes no webhook surface at all (registre#44): the path is
 * {@code null} — the webhook controller registers no route — and the
 * webhook-only SPI methods fail closed instead of verifying-as-pass.
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
    void webhookPathIsNullSoNoRouteIsRegistered() {
        assertNull(channel.webhookPath(),
                "a Gateway channel must not expose an unauthenticated webhook route");
    }

    @Test
    void maxMessageLengthIs2000() {
        assertEquals(2000, channel.maxMessageLength());
    }

    @Test
    void verifySignatureFailsClosedRatherThanVerifyingAsPass() {
        assertThrows(ChannelException.class,
                () -> channel.verifySignature(Map.of(), new byte[0]));
    }

    @Test
    void receiveFailsClosedBecauseGatewayHandlesMessages() {
        assertThrows(ChannelException.class,
                () -> channel.receive(Map.of(), new byte[0]));
    }
}
