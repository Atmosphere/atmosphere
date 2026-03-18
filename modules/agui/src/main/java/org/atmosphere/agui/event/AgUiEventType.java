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
package org.atmosphere.agui.event;

/**
 * All 28 AG-UI event type string constants as defined by the AG-UI protocol specification.
 * Each enum constant maps to its wire-format event type string.
 */
public enum AgUiEventType {

    // Lifecycle (5)
    RUN_STARTED("RUN_STARTED"),
    RUN_FINISHED("RUN_FINISHED"),
    RUN_ERROR("RUN_ERROR"),
    STEP_STARTED("STEP_STARTED"),
    STEP_FINISHED("STEP_FINISHED"),

    // Text Messages (4)
    TEXT_MESSAGE_START("TEXT_MESSAGE_START"),
    TEXT_MESSAGE_CONTENT("TEXT_MESSAGE_CONTENT"),
    TEXT_MESSAGE_END("TEXT_MESSAGE_END"),
    TEXT_MESSAGE_CHUNK("TEXT_MESSAGE_CHUNK"),

    // Tool Calls (5)
    TOOL_CALL_START("TOOL_CALL_START"),
    TOOL_CALL_ARGS("TOOL_CALL_ARGS"),
    TOOL_CALL_END("TOOL_CALL_END"),
    TOOL_CALL_RESULT("TOOL_CALL_RESULT"),
    TOOL_CALL_CHUNK("TOOL_CALL_CHUNK"),

    // State (3)
    STATE_SNAPSHOT("STATE_SNAPSHOT"),
    STATE_DELTA("STATE_DELTA"),
    MESSAGES_SNAPSHOT("MESSAGES_SNAPSHOT"),

    // Reasoning (6)
    REASONING_START("REASONING_START"),
    REASONING_MESSAGE_START("REASONING_MESSAGE_START"),
    REASONING_MESSAGE_CONTENT("REASONING_MESSAGE_CONTENT"),
    REASONING_MESSAGE_END("REASONING_MESSAGE_END"),
    REASONING_MESSAGE_CHUNK("REASONING_MESSAGE_CHUNK"),
    REASONING_END("REASONING_END"),

    // Activity (2)
    ACTIVITY_SNAPSHOT("ACTIVITY_SNAPSHOT"),
    ACTIVITY_DELTA("ACTIVITY_DELTA"),

    // Special (2)
    RAW("RAW"),
    CUSTOM("CUSTOM");

    private final String value;

    AgUiEventType(String value) {
        this.value = value;
    }

    /**
     * Returns the wire-format event type string.
     */
    public String value() {
        return value;
    }
}
