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
    private DiscordProperties discord = new DiscordProperties();
    private WhatsAppProperties whatsapp = new WhatsAppProperties();
    private MessengerProperties messenger = new MessengerProperties();

    public TelegramProperties getTelegram() { return telegram; }
    public void setTelegram(TelegramProperties telegram) { this.telegram = telegram; }

    public SlackProperties getSlack() { return slack; }
    public void setSlack(SlackProperties slack) { this.slack = slack; }

    public DiscordProperties getDiscord() { return discord; }
    public void setDiscord(DiscordProperties discord) { this.discord = discord; }

    public WhatsAppProperties getWhatsapp() { return whatsapp; }
    public void setWhatsapp(WhatsAppProperties whatsapp) { this.whatsapp = whatsapp; }

    public MessengerProperties getMessenger() { return messenger; }
    public void setMessenger(MessengerProperties messenger) { this.messenger = messenger; }

    public static class TelegramProperties {
        private String botToken;
        private String webhookSecret = "";

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    }

    public static class SlackProperties {
        private String botToken;
        private String signingSecret;

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    }

    public static class DiscordProperties {
        private String botToken;
        private String publicKey;

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }

    public static class WhatsAppProperties {
        private String phoneNumberId;
        private String accessToken;
        private String appSecret;

        public String getPhoneNumberId() { return phoneNumberId; }
        public void setPhoneNumberId(String phoneNumberId) { this.phoneNumberId = phoneNumberId; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    }

    public static class MessengerProperties {
        private String pageAccessToken;
        private String appSecret;

        public String getPageAccessToken() { return pageAccessToken; }
        public void setPageAccessToken(String pageAccessToken) { this.pageAccessToken = pageAccessToken; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    }
}
