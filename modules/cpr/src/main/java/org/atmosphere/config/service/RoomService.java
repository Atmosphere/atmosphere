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
package org.atmosphere.config.service;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Room handler. Methods annotated with {@link Message},
 * {@link Ready}, and {@link Disconnect} will be invoked for room lifecycle
 * events, similar to {@link ManagedService} but scoped to a {@link org.atmosphere.room.Room}.
 *
 * <p>Example:
 * <pre>{@code
 * @RoomService(path = "/chat/{roomId}")
 * public class ChatRoom {
 *
 *     @Ready
 *     public void onJoin(AtmosphereResource r) {
 *         // invoked when a client joins the room
 *     }
 *
 *     @Message
 *     public String onMessage(String message) {
 *         return message; // broadcast to all room members
 *     }
 *
 *     @Disconnect
 *     public void onLeave(AtmosphereResourceEvent event) {
 *         // invoked when a client disconnects
 *     }
 * }
 * }</pre>
 *
 * @since 4.0
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RoomService {

    /**
     * The mapping path for this room handler. Supports path parameters
     * (e.g. {@code /chat/{roomId}}).
     *
     * @return the mapping path
     */
    String path() default "/";

    /**
     * Maximum number of messages to keep in the room history.
     * Set to 0 to disable history (default).
     *
     * @return the max history size
     */
    int maxHistory() default 0;
}
