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
package org.atmosphere.a2a.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies the sender of an A2A {@link Message}. Wire encoding follows the
 * ADR-001 ProtoJSON enum convention introduced in A2A v1.0.0
 * ({@code ROLE_USER} / {@code ROLE_AGENT}); the deserializer accepts the
 * legacy short names ({@code user} / {@code agent}) for back-compat with
 * pre-1.0 drafts.
 */
public enum Role {
    USER("ROLE_USER"),
    AGENT("ROLE_AGENT");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Role fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (Role r : values()) {
            if (r.wire.equalsIgnoreCase(value) || r.name().equalsIgnoreCase(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown A2A role: " + value);
    }
}
