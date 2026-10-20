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
import org.atmosphere.cpr.AtmosphereResourceImpl;

/**
 * A room whose {@code @Disconnect} reads the leaving member's request, after
 * another thread has taken the resource out of scope: the interleaving a
 * broadcast whose write failed (or the close thread, when a broadcast thread
 * runs the callback) produces by chance. The callback forces it so the test
 * is deterministic.
 */
@ManagedService(path = "/leave-race")
public class LeaveRaceHandler {

    static final String ROOM_ATTRIBUTE = "leave-race.room";

    @Ready
    public void onReady(AtmosphereResource r) {
        r.getRequest().setAttribute(ROOM_ATTRIBUTE, "math");
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) throws InterruptedException {
        var resource = event.getResource();
        if (resource == null) {
            return;
        }
        var teardown = new Thread(() -> ((AtmosphereResourceImpl) resource).setIsInScope(false));
        teardown.start();
        teardown.join();
        var room = resource.getRequest().getAttribute(ROOM_ATTRIBUTE);
        resource.getBroadcaster().broadcast("left:" + room);
    }

    @Message
    public String onMessage(String message) {
        return message;
    }
}
