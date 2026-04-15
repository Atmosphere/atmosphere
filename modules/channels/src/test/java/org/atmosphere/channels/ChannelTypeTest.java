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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChannelTypeTest {

    @Test
    void telegramProperties() {
        assertEquals("telegram", ChannelType.TELEGRAM.id());
        assertEquals(4096, ChannelType.TELEGRAM.maxLength());
    }

    @Test
    void slackProperties() {
        assertEquals("slack", ChannelType.SLACK.id());
        assertEquals(40_000, ChannelType.SLACK.maxLength());
    }

    @Test
    void discordProperties() {
        assertEquals("discord", ChannelType.DISCORD.id());
        assertEquals(2000, ChannelType.DISCORD.maxLength());
    }

    @Test
    void whatsappProperties() {
        assertEquals("whatsapp", ChannelType.WHATSAPP.id());
        assertEquals(4096, ChannelType.WHATSAPP.maxLength());
    }

    @Test
    void messengerProperties() {
        assertEquals("messenger", ChannelType.MESSENGER.id());
        assertEquals(2000, ChannelType.MESSENGER.maxLength());
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void allChannelTypesHaveNonNullId(ChannelType type) {
        assertNotNull(type.id());
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void allChannelTypesHavePositiveMaxLength(ChannelType type) {
        assertEquals(true, type.maxLength() > 0);
    }

    @Test
    void enumValuesCount() {
        assertEquals(5, ChannelType.values().length);
    }

    @Test
    void valueOfRoundTrips() {
        for (var type : ChannelType.values()) {
            assertEquals(type, ChannelType.valueOf(type.name()));
        }
    }
}
