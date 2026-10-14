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

import org.atmosphere.channels.discord.DiscordChannel;
import org.atmosphere.channels.messenger.MessengerChannel;
import org.atmosphere.channels.slack.SlackChannel;
import org.atmosphere.channels.telegram.TelegramChannel;
import org.atmosphere.channels.whatsapp.WhatsAppChannel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A channel activates only when its credential is actually set. The
 * spring-boot-channels-chat sample declares every credential as an
 * empty-default placeholder ({@code ${TELEGRAM_BOT_TOKEN:}}), and a bare
 * {@code @ConditionalOnProperty} matches an empty value, so every channel
 * registered a webhook with a blank token while the README and the yml said
 * "activates when credentials are set". Driven through a real context because
 * the defect lives in the condition, not in the bean methods.
 */
class ChannelsAutoConfigurationBlankTokenTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChannelsAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void emptyAndBlankCredentialsRegisterNoChannel() {
        runner.withPropertyValues(
                        "atmosphere.channels.telegram.bot-token=",
                        "atmosphere.channels.slack.bot-token=",
                        "atmosphere.channels.discord.bot-token=   ",
                        "atmosphere.channels.whatsapp.access-token=",
                        "atmosphere.channels.messenger.page-access-token=")
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null,
                            "context must start: " + context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(TelegramChannel.class).size(), "telegram");
                    assertEquals(0, context.getBeansOfType(SlackChannel.class).size(), "slack");
                    assertEquals(0, context.getBeansOfType(DiscordChannel.class).size(), "discord");
                    assertEquals(0, context.getBeansOfType(WhatsAppChannel.class).size(), "whatsapp");
                    assertEquals(0, context.getBeansOfType(MessengerChannel.class).size(), "messenger");
                    assertEquals(0, context.getBeansOfType(MessagingChannel.class).size(),
                            "no channel may run without a credential");
                    assertEquals(List.of(), context.getBean(ChannelAiBridge.class).runningChannels(),
                            "the bridge reports the channels actually running");
                });
    }

    @Test
    void setCredentialsRegisterTheirChannels() {
        runner.withPropertyValues(
                        "atmosphere.channels.telegram.bot-token=123:abc",
                        "atmosphere.channels.telegram.webhook-secret=s",
                        "atmosphere.channels.slack.bot-token=xoxb-1",
                        "atmosphere.channels.slack.signing-secret=s",
                        "atmosphere.channels.whatsapp.access-token=EAAG",
                        "atmosphere.channels.whatsapp.phone-number-id=1",
                        "atmosphere.channels.whatsapp.app-secret=s",
                        "atmosphere.channels.messenger.page-access-token=EAAP",
                        "atmosphere.channels.messenger.app-secret=s")
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null,
                            "context must start: " + context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(TelegramChannel.class).size(), "telegram");
                    assertEquals(1, context.getBeansOfType(SlackChannel.class).size(), "slack");
                    assertEquals(1, context.getBeansOfType(WhatsAppChannel.class).size(), "whatsapp");
                    assertEquals(1, context.getBeansOfType(MessengerChannel.class).size(), "messenger");
                    assertEquals(4, context.getBeansOfType(MessagingChannel.class).size());
                    assertEquals(List.of("messenger", "slack", "telegram", "whatsapp"),
                            context.getBean(ChannelAiBridge.class).runningChannels());
                });
    }
}
