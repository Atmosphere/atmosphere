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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelsPropertiesTest {

    @Test
    void defaultNestedPropertiesAreNonNull() {
        var props = new ChannelsProperties();
        assertNotNull(props.getTelegram());
        assertNotNull(props.getSlack());
        assertNotNull(props.getDiscord());
        assertNotNull(props.getWhatsapp());
        assertNotNull(props.getMessenger());
    }

    // --- Telegram ---

    @Test
    void telegramBotTokenDefaultsToNull() {
        assertNull(new ChannelsProperties().getTelegram().getBotToken());
    }

    @Test
    void telegramWebhookSecretDefaultsToEmpty() {
        assertEquals("", new ChannelsProperties().getTelegram().getWebhookSecret());
    }

    @Test
    void telegramPropertiesGettersAndSetters() {
        var telegram = new ChannelsProperties.TelegramProperties();
        telegram.setBotToken("bot123");
        telegram.setWebhookSecret("secret456");
        assertEquals("bot123", telegram.getBotToken());
        assertEquals("secret456", telegram.getWebhookSecret());
    }

    // --- Slack ---

    @Test
    void slackPropertiesDefaultToNull() {
        var slack = new ChannelsProperties.SlackProperties();
        assertNull(slack.getBotToken());
        assertNull(slack.getSigningSecret());
    }

    @Test
    void slackPropertiesGettersAndSetters() {
        var slack = new ChannelsProperties.SlackProperties();
        slack.setBotToken("xoxb-token");
        slack.setSigningSecret("signing-secret");
        assertEquals("xoxb-token", slack.getBotToken());
        assertEquals("signing-secret", slack.getSigningSecret());
    }

    // --- Discord ---

    @Test
    void discordPropertiesDefaultToNull() {
        assertNull(new ChannelsProperties.DiscordProperties().getBotToken());
    }

    @Test
    void discordPropertiesGettersAndSetters() {
        var discord = new ChannelsProperties.DiscordProperties();
        discord.setBotToken("discord-bot-token");
        assertEquals("discord-bot-token", discord.getBotToken());
    }

    // --- WhatsApp ---

    @Test
    void whatsappPropertiesDefaultToNull() {
        var wa = new ChannelsProperties.WhatsAppProperties();
        assertNull(wa.getPhoneNumberId());
        assertNull(wa.getAccessToken());
        assertNull(wa.getAppSecret());
    }

    @Test
    void whatsappPropertiesGettersAndSetters() {
        var wa = new ChannelsProperties.WhatsAppProperties();
        wa.setPhoneNumberId("12345");
        wa.setAccessToken("access-token");
        wa.setAppSecret("app-secret");
        assertEquals("12345", wa.getPhoneNumberId());
        assertEquals("access-token", wa.getAccessToken());
        assertEquals("app-secret", wa.getAppSecret());
    }

    // --- Messenger ---

    @Test
    void messengerPropertiesDefaultToNull() {
        var messenger = new ChannelsProperties.MessengerProperties();
        assertNull(messenger.getPageAccessToken());
        assertNull(messenger.getAppSecret());
    }

    @Test
    void messengerPropertiesGettersAndSetters() {
        var messenger = new ChannelsProperties.MessengerProperties();
        messenger.setPageAccessToken("page-token");
        messenger.setAppSecret("messenger-secret");
        assertEquals("page-token", messenger.getPageAccessToken());
        assertEquals("messenger-secret", messenger.getAppSecret());
    }

    // --- Top-level setters ---

    @Test
    void setTelegramReplacesInstance() {
        var props = new ChannelsProperties();
        var telegram = new ChannelsProperties.TelegramProperties();
        telegram.setBotToken("new-bot");
        props.setTelegram(telegram);
        assertEquals("new-bot", props.getTelegram().getBotToken());
    }

    @Test
    void setSlackReplacesInstance() {
        var props = new ChannelsProperties();
        var slack = new ChannelsProperties.SlackProperties();
        slack.setBotToken("new-slack");
        props.setSlack(slack);
        assertEquals("new-slack", props.getSlack().getBotToken());
    }

    @Test
    void setDiscordReplacesInstance() {
        var props = new ChannelsProperties();
        var discord = new ChannelsProperties.DiscordProperties();
        discord.setBotToken("new-discord");
        props.setDiscord(discord);
        assertEquals("new-discord", props.getDiscord().getBotToken());
    }

    @Test
    void setWhatsappReplacesInstance() {
        var props = new ChannelsProperties();
        var wa = new ChannelsProperties.WhatsAppProperties();
        wa.setPhoneNumberId("99999");
        props.setWhatsapp(wa);
        assertEquals("99999", props.getWhatsapp().getPhoneNumberId());
    }

    @Test
    void setMessengerReplacesInstance() {
        var props = new ChannelsProperties();
        var messenger = new ChannelsProperties.MessengerProperties();
        messenger.setPageAccessToken("new-page-token");
        props.setMessenger(messenger);
        assertEquals("new-page-token", props.getMessenger().getPageAccessToken());
    }
}
