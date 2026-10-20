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
package org.atmosphere.integrationtests.disconnect;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A room that streams frames to its members on request and records every
 * {@code @Disconnect} it is given: the tracking id of the resource it carried,
 * or {@code null} when the event came without one.
 */
@ManagedService(path = "/abrupt-drop")
public class AbruptDropHandler {

    /** Per test run: the {@code @Disconnect} calls, by the resource uuid seen (null when none). */
    static final List<String> DISCONNECTS = new CopyOnWriteArrayList<>();

    static final ConcurrentHashMap<String, Boolean> STREAMING = new ConcurrentHashMap<>();

    static volatile Broadcaster room;

    @Ready
    public void onReady(AtmosphereResource resource) {
        room = resource.getBroadcaster();
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        var resource = event.getResource();
        DISCONNECTS.add(resource == null ? "null" : resource.uuid());
    }

    /** "stream": broadcast a long run of frames, as an AI answer streams into a room. */
    @Message
    public String onMessage(String message) {
        if ("stream".equals(message) && STREAMING.putIfAbsent("on", Boolean.TRUE) == null) {
            var broadcaster = room;
            Thread.ofVirtual().start(() -> {
                try {
                    for (var i = 0; i < 2_000; i++) {
                        broadcaster.broadcast("frame-" + i);
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    STREAMING.remove("on");
                }
            });
            return null;
        }
        return message;
    }
}
