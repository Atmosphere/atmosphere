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

import java.time.Duration;

/**
 * Configuration properties for Atmosphere messaging channels.
 */
public class ChannelsProperties {

    private TelegramProperties telegram = new TelegramProperties();
    private SlackProperties slack = new SlackProperties();
    private DiscordProperties discord = new DiscordProperties();
    private WhatsAppProperties whatsapp = new WhatsAppProperties();
    private MessengerProperties messenger = new MessengerProperties();
    private DedupProperties dedup = new DedupProperties();

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

    public DedupProperties getDedup() { return dedup; }
    public void setDedup(DedupProperties dedup) { this.dedup = dedup; }

    /**
     * Inbound webhook idempotency. Every supported platform re-delivers a
     * webhook when the endpoint answers non-2xx or times out, so without a
     * dedup key a single user message can run the agent — and bill for the
     * model call — two or three times.
     *
     * <pre>
     * atmosphere:
     *   channels:
     *     dedup:
     *       enabled: true      # default
     *       max-entries: 10000 # default, hard bound on the cache
     *       ttl: 15m           # default, how long an id is remembered
     * </pre>
     */
    public static class DedupProperties {
        private boolean enabled = true;
        private int maxEntries = SeenMessageCache.DEFAULT_MAX_ENTRIES;
        private Duration ttl = SeenMessageCache.DEFAULT_TTL;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }

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

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
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
