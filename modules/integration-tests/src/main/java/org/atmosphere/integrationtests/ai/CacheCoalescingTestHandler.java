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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.cache.AiResponseCacheInspector;
import org.atmosphere.ai.cache.AiResponseCacheListener;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;

/**
 * Test handler for /ai/cache-coalescing endpoint.
 * Registers a CoalescedCacheEventListener and sends coalesced data as metadata.
 */
public class CacheCoalescingTestHandler implements AtmosphereHandler {

    private volatile boolean cacheConfigured = false;
    private final AiResponseCacheListener cacheListener = new AiResponseCacheListener();

    public CacheCoalescingTestHandler() {
        cacheListener.addCoalescedListener(event -> {
            // The coalesced event fires after the session completes, so we can't
            // send metadata on the same session. Instead, log it for verification.
            // In a real scenario, this would push to a metrics system.
            System.out.println("COALESCED:" + event.sessionId()
                    + ":tokens=" + event.totalTokens()
                    + ":status=" + event.status()
                    + ":elapsed=" + event.elapsedMs());
        });
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        if (!cacheConfigured) {
            configureCache(resource);
            cacheConfigured = true;
        }
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("coalescing-handler").start(() -> {
                var session = StreamingSessions.start(resource);
                // Send metadata about the coalesced listener being active
                session.sendMetadata("coalescing.enabled", true);
                var client = FakeLlmClient.slow("coalescing-model", 10,
                        "Token1.", " Token2.", " Token3.", " Token4.", " Token5.");
                var request = ChatCompletionRequest.of(client.modelName(), trimmed);
                client.streamChatCompletion(request, session);
            });
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw) {
            var inner = raw.message();
            if (inner instanceof String json) {
                event.getResource().getResponse().write(json);
                event.getResource().getResponse().flushBuffer();
            }
        }
    }

    @Override
    public void destroy() {
    }

    private void configureCache(AtmosphereResource resource) {
        var broadcasterConfig = resource.getBroadcaster().getBroadcasterConfig();
        var atmosphereConfig = resource.getAtmosphereConfig();
        var cache = new UUIDBroadcasterCache();
        cache.configure(atmosphereConfig);
        cache.inspector(new AiResponseCacheInspector());
        cache.addBroadcasterCacheListener(cacheListener);
        broadcasterConfig.setBroadcasterCache(cache);
        cache.start();
    }
}
