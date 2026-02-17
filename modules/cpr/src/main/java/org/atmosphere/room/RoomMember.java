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

import java.util.Map;
import java.util.Objects;

/**
 * Application-level identity for a room member. Unlike
 * {@link org.atmosphere.cpr.AtmosphereResource#uuid()}, which is
 * connection-scoped, a {@code RoomMember.id} is stable across reconnects.
 *
 * @param id       application-level member identifier (e.g. username)
 * @param metadata arbitrary key-value pairs (e.g. avatar URL, display name)
 * @since 4.0
 */
public record RoomMember(String id, Map<String, Object> metadata) {

    public RoomMember {
        Objects.requireNonNull(id, "member id must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Convenience constructor with no metadata.
     *
     * @param id application-level member identifier
     */
    public RoomMember(String id) {
        this(id, Map.of());
    }
}
