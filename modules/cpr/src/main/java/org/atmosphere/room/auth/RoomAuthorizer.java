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
package org.atmosphere.room.auth;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.room.RoomAction;

/**
 * Authorizes room operations. Implementations decide whether a given
 * resource is allowed to perform an action in a room.
 *
 * <pre>{@code
 * public class MyAuthorizer implements RoomAuthorizer {
 *     @Override
 *     public boolean authorize(AtmosphereResource resource, String roomName, RoomAction action) {
 *         return resource.getRequest().isUserInRole("CHAT_USER");
 *     }
 * }
 * }</pre>
 *
 * @since 4.0
 * @see RoomAuth
 */
@FunctionalInterface
public interface RoomAuthorizer {

    /**
     * Check if the resource is authorized to perform the action in the room.
     *
     * @param resource the requesting resource
     * @param roomName the target room name
     * @param action   the action being attempted
     * @return true if authorized
     */
    boolean authorize(AtmosphereResource resource, String roomName, RoomAction action);
}
