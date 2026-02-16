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
package org.atmosphere.room;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interceptor that auto-joins resources to rooms based on URL path.
 * <p>
 * Given a base path (default {@code /room/}), any request to
 * {@code /room/lobby} will auto-join the resource to a room named "lobby".
 *
 * <pre>{@code
 * // Configure with framework
 * RoomManager rooms = RoomManager.create(framework);
 * framework.interceptor(new RoomInterceptor(rooms));
 *
 * // Or with a custom base path
 * framework.interceptor(new RoomInterceptor(rooms, "/chat/"));
 * }</pre>
 *
 * @since 4.0
 */
public class RoomInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RoomInterceptor.class);
    private static final String DEFAULT_BASE_PATH = "/room/";

    private final RoomManager roomManager;
    private final String basePath;

    public RoomInterceptor(RoomManager roomManager) {
        this(roomManager, DEFAULT_BASE_PATH);
    }

    public RoomInterceptor(RoomManager roomManager, String basePath) {
        this.roomManager = roomManager;
        this.basePath = basePath.endsWith("/") ? basePath : basePath + "/";
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        super.inspect(r);

        String pathInfo = r.getRequest().getPathInfo();
        if (pathInfo == null) {
            pathInfo = r.getRequest().getRequestURI();
        }

        if (pathInfo != null && pathInfo.startsWith(basePath)) {
            String roomName = pathInfo.substring(basePath.length());
            // Remove trailing slash if present
            if (roomName.endsWith("/")) {
                roomName = roomName.substring(0, roomName.length() - 1);
            }
            if (!roomName.isEmpty() && !roomName.contains("/")) {
                Room room = roomManager.room(roomName);
                room.join(r);
                logger.debug("Auto-joined {} to room '{}' via path {}", r.uuid(), roomName, pathInfo);
            }
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "RoomInterceptor{basePath='" + basePath + "'}";
    }
}
