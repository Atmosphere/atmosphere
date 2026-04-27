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
 * Lifecycle states for an A2A task as defined in the v1.0.0 specification.
 * Wire form follows the ADR-001 ProtoJSON enum convention
 * ({@code TASK_STATE_WORKING} etc.); deserialization additionally accepts the
 * Java enum constant name ({@code WORKING}) and the lowercase pre-1.0 form.
 *
 * <p>{@link #SUBMITTED} was added in v1.0.0 to model the ACK-before-WORK
 * transition that older drafts collapsed into {@link #WORKING}.</p>
 */
public enum TaskState {
    UNSPECIFIED("TASK_STATE_UNSPECIFIED"),
    SUBMITTED("TASK_STATE_SUBMITTED"),
    WORKING("TASK_STATE_WORKING"),
    COMPLETED("TASK_STATE_COMPLETED"),
    FAILED("TASK_STATE_FAILED"),
    CANCELED("TASK_STATE_CANCELED"),
    INPUT_REQUIRED("TASK_STATE_INPUT_REQUIRED"),
    REJECTED("TASK_STATE_REJECTED"),
    AUTH_REQUIRED("TASK_STATE_AUTH_REQUIRED");

    private final String wire;

    TaskState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** True when this state is one from which no further progress will occur. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }

    /** True when this state pauses the task pending external input. */
    public boolean isInterrupted() {
        return this == INPUT_REQUIRED || this == AUTH_REQUIRED;
    }

    @JsonCreator
    public static TaskState fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (TaskState s : values()) {
            if (s.wire.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown A2A task state: " + value);
    }
}
