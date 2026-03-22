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

import java.util.List;

import org.atmosphere.channels.discord.DiscordChannel;
import org.atmosphere.channels.messenger.MessengerChannel;
import org.atmosphere.channels.slack.SlackChannel;
import org.atmosphere.channels.telegram.TelegramChannel;
import org.atmosphere.channels.whatsapp.WhatsAppChannel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot auto-configuration for Atmosphere messaging channels.
 * <p>
 * Each channel activates when its credentials are configured:
 * <pre>
 * atmosphere:
 *   channels:
 *     telegram:
 *       bot-token: ${TELEGRAM_BOT_TOKEN}
 *       webhook-secret: ${TELEGRAM_WEBHOOK_SECRET}
 *     slack:
 *       bot-token: ${SLACK_BOT_TOKEN}
 *       signing-secret: ${SLACK_SIGNING_SECRET}
 *     whatsapp:
 *       phone-number-id: ${WHATSAPP_PHONE_NUMBER_ID}
 *       access-token: ${WHATSAPP_ACCESS_TOKEN}
 *       app-secret: ${WHATSAPP_APP_SECRET}
 *     discord:
 *       bot-token: ${DISCORD_BOT_TOKEN}
 *     messenger:
 *       page-access-token: ${MESSENGER_PAGE_TOKEN}
 *       app-secret: ${MESSENGER_APP_SECRET}
 * </pre>
 */
@AutoConfiguration
public class ChannelsAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "atmosphere.channels")
    public ChannelsProperties channelsProperties() {
        return new ChannelsProperties();
    }

    @Bean
    @ConditionalOnProperty("atmosphere.channels.telegram.bot-token")
    public TelegramChannel telegramChannel(ChannelsProperties props, ObjectMapper objectMapper) {
        var telegram = props.getTelegram();
        return new TelegramChannel(telegram.getBotToken(), telegram.getWebhookSecret(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty("atmosphere.channels.slack.bot-token")
    public SlackChannel slackChannel(ChannelsProperties props, ObjectMapper objectMapper) {
        var slack = props.getSlack();
        return new SlackChannel(slack.getBotToken(), slack.getSigningSecret(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty("atmosphere.channels.discord.bot-token")
    public DiscordChannel discordChannel(ChannelsProperties props, ObjectMapper objectMapper,
                                         ChannelWebhookController webhookController) {
        var discord = props.getDiscord();
        var channel = new DiscordChannel(discord.getBotToken(), objectMapper,
                webhookController::routeMessage);
        channel.start();
        return channel;
    }

    @Bean
    @ConditionalOnProperty("atmosphere.channels.whatsapp.access-token")
    public WhatsAppChannel whatsAppChannel(ChannelsProperties props, ObjectMapper objectMapper) {
        var whatsapp = props.getWhatsapp();
        return new WhatsAppChannel(whatsapp.getPhoneNumberId(), whatsapp.getAccessToken(),
                whatsapp.getAppSecret(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty("atmosphere.channels.messenger.page-access-token")
    public MessengerChannel messengerChannel(ChannelsProperties props, ObjectMapper objectMapper) {
        var messenger = props.getMessenger();
        return new MessengerChannel(messenger.getPageAccessToken(), messenger.getAppSecret(),
                objectMapper);
    }

    @Bean
    public ChannelWebhookController channelWebhookController(List<MessagingChannel> channels) {
        return new ChannelWebhookController(channels);
    }
}
