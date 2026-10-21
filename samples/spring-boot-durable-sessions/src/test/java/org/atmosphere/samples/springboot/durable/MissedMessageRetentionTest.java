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
package org.atmosphere.samples.springboot.durable;

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_CLIENT_IDLETIME;
import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_MESSAGE_TTL;

/**
 * The chat's broadcaster cache keeps a disconnected client's missed messages
 * for as long as the bundled Console keeps trying to reconnect. The 2026-10-20
 * sweep lost every message broadcast during a 104 s outage: the Console was
 * still retrying, but the cache had dropped the client after the default 60 s.
 */
// A real server, so the Atmosphere servlet initializes with its init parameters
// and registers the @ManagedService broadcaster with its cache.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MissedMessageRetentionTest {

    /**
     * The Console's reconnect schedule (modules/spring-boot-starter/frontend:
     * {@code MAX_RECONNECT_ON_CLOSE} in useAtmosphereChat.ts,
     * {@code RECONNECT_INTERVAL_MS} and {@code MAX_RECONNECT_WAIT_MS} in
     * transports/atmosphere.ts). Each wait is that delay times a jitter below 1.
     */
    private static final int CONSOLE_RECONNECT_ATTEMPTS = 10;
    private static final long CONSOLE_RECONNECT_INTERVAL_MS = 3_000;
    private static final long CONSOLE_MAX_RECONNECT_WAIT_MS = 25_000;

    @Autowired
    private AtmosphereFramework framework;

    private static long longestConsoleRetryWindowSeconds() {
        long millis = IntStream.rangeClosed(1, CONSOLE_RECONNECT_ATTEMPTS)
                .mapToLong(attempt -> Math.min(
                        CONSOLE_RECONNECT_INTERVAL_MS * (1L << Math.min(attempt - 1, 5)),
                        CONSOLE_MAX_RECONNECT_WAIT_MS))
                .sum();
        return millis / 1000;
    }

    @Test
    void theChatCachesMissedMessagesPerClient() {
        var chat = framework.getBroadcasterFactory().lookup("/atmosphere/chat", true);
        assertThat(chat.getBroadcasterConfig().getBroadcasterCache())
                .as("the clientIdleTime below only applies to a UUIDBroadcasterCache")
                .isInstanceOf(UUIDBroadcasterCache.class);
    }

    @Test
    void theCacheKeepsAClientAtLeastAsLongAsTheConsoleRetries() {
        long window = longestConsoleRetryWindowSeconds();
        assertThat(window).as("the Console's longest retry window").isEqualTo(195);

        long clientIdleSeconds = Long.parseLong(
                framework.getAtmosphereConfig().getInitParameter(UUIDBROADCASTERCACHE_CLIENT_IDLETIME, "60"));
        assertThat(clientIdleSeconds)
                .as("UUIDBroadcasterCache.clientIdleTime (s) must cover the Console's %d s of retries", window)
                .isGreaterThanOrEqualTo(window);

        long messageTtlSeconds = Long.parseLong(
                framework.getAtmosphereConfig().getInitParameter(UUIDBROADCASTERCACHE_MESSAGE_TTL, "300"));
        assertThat(messageTtlSeconds)
                .as("UUIDBroadcasterCache.messageTTL (s) must cover the Console's %d s of retries", window)
                .isGreaterThanOrEqualTo(window);
    }

    @Test
    void theChatsCacheKeepsAClientDisconnectedForTheWholeRetryWindow() throws Exception {
        // The init parameter is what the cache reads; this checks the instance
        // the chat broadcaster actually uses applied it. A client last seen 200 s
        // ago (past the 195 s window, well past the 60 s default) keeps its
        // missed message through a reaper pass; one last seen 400 s ago does not.
        var chat = framework.getBroadcasterFactory().lookup("/atmosphere/chat", true);
        var cache = (UUIDBroadcasterCache) chat.getBroadcasterConfig().getBroadcasterCache();
        var reaper = UUIDBroadcasterCache.class.getDeclaredMethod("invalidateExpiredEntries");
        reaper.setAccessible(true);

        var away = "retention-away-" + UUID.randomUUID();
        var gone = "retention-gone-" + UUID.randomUUID();
        cache.addToCache(chat.getID(), away, new BroadcastMessage("missed while away"));
        cache.addToCache(chat.getID(), gone, new BroadcastMessage("missed long ago"));
        var now = System.currentTimeMillis();
        cache.activeClients().put(away, now - 200_000);
        cache.activeClients().put(gone, now - 400_000);

        reaper.invoke(cache);

        assertThat(cache.messages()).as("a client away 200 s keeps what it missed").containsKey(away);
        assertThat(cache.messages()).as("a client away 400 s is dropped").doesNotContainKey(gone);
        cache.messages().remove(away);
        cache.activeClients().remove(away);
    }
}
