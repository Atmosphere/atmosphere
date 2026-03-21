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

/**
 * Configuration properties for Atmosphere messaging channels.
 */
public class ChannelsProperties {

    private TelegramProperties telegram = new TelegramProperties();
    private SlackProperties slack = new SlackProperties();

    public TelegramProperties getTelegram() {
        return telegram;
    }

    public void setTelegram(TelegramProperties telegram) {
        this.telegram = telegram;
    }

    public SlackProperties getSlack() {
        return slack;
    }

    public void setSlack(SlackProperties slack) {
        this.slack = slack;
    }

    public static class TelegramProperties {
        private String botToken;
        private String webhookSecret = "";

        public String getBotToken() {
            return botToken;
        }

        public void setBotToken(String botToken) {
            this.botToken = botToken;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }
    }

    public static class SlackProperties {
        private String botToken;
        private String signingSecret;

        public String getBotToken() {
            return botToken;
        }

        public void setBotToken(String botToken) {
            this.botToken = botToken;
        }

        public String getSigningSecret() {
            return signingSecret;
        }

        public void setSigningSecret(String signingSecret) {
            this.signingSecret = signingSecret;
        }
    }
}
