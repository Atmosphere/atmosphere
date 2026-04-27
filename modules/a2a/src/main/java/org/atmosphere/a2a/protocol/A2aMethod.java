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
package org.atmosphere.a2a.protocol;

import java.util.Map;

/**
 * JSON-RPC method names defined by the A2A v1.0.0 specification (PascalCase
 * per the {@code SendMessage}/{@code GetTask}/etc. naming finalized in
 * {@code docs/specification.md} §9.4).
 *
 * <p>The {@link #LEGACY_ALIASES} map normalizes pre-1.0 slash-style names
 * ({@code message/send}, {@code tasks/get}, {@code tasks/pushNotification/*},
 * {@code agent/authenticatedExtendedCard}, etc.) to their v1.0.0 equivalents.
 * The protocol handler logs a one-time WARN per legacy method seen and
 * dispatches as if the new name had been used; this keeps existing Atmosphere
 * clients functional during migration without compromising spec conformance
 * for new clients.</p>
 */
public final class A2aMethod {
    private A2aMethod() {
    }

    /** Send a message to initiate or continue a task. */
    public static final String SEND_MESSAGE = "SendMessage";
    /** Send a message and stream Server-Sent Events for incremental updates. */
    public static final String SEND_STREAMING_MESSAGE = "SendStreamingMessage";
    /** Retrieve the current state of a task by id. */
    public static final String GET_TASK = "GetTask";
    /** Paginated task list with optional context/status/timestamp filters. */
    public static final String LIST_TASKS = "ListTasks";
    /** Cancel an in-flight task. */
    public static final String CANCEL_TASK = "CancelTask";
    /** Subscribe to streamed updates for a non-terminal task. */
    public static final String SUBSCRIBE_TO_TASK = "SubscribeToTask";

    /** Register a push-notification webhook for a task. */
    public static final String CREATE_TASK_PUSH_NOTIFICATION_CONFIG =
            "CreateTaskPushNotificationConfig";
    /** Retrieve a previously registered push-notification config. */
    public static final String GET_TASK_PUSH_NOTIFICATION_CONFIG =
            "GetTaskPushNotificationConfig";
    /** List all push-notification configs registered for a task. */
    public static final String LIST_TASK_PUSH_NOTIFICATION_CONFIGS =
            "ListTaskPushNotificationConfigs";
    /** Delete a push-notification config. */
    public static final String DELETE_TASK_PUSH_NOTIFICATION_CONFIG =
            "DeleteTaskPushNotificationConfig";

    /** Retrieve the authenticated extended Agent Card. */
    public static final String GET_EXTENDED_AGENT_CARD = "GetExtendedAgentCard";

    /**
     * Pre-1.0 slash-style method names to their v1.0.0 PascalCase equivalents.
     * Lookup is case-sensitive (consistent with JSON-RPC method semantics).
     */
    public static final Map<String, String> LEGACY_ALIASES = Map.ofEntries(
            Map.entry("message/send", SEND_MESSAGE),
            Map.entry("message/stream", SEND_STREAMING_MESSAGE),
            Map.entry("tasks/get", GET_TASK),
            Map.entry("tasks/list", LIST_TASKS),
            Map.entry("tasks/cancel", CANCEL_TASK),
            Map.entry("tasks/resubscribe", SUBSCRIBE_TO_TASK),
            Map.entry("tasks/pushNotification/set", CREATE_TASK_PUSH_NOTIFICATION_CONFIG),
            Map.entry("tasks/pushNotification/get", GET_TASK_PUSH_NOTIFICATION_CONFIG),
            Map.entry("tasks/pushNotification/list", LIST_TASK_PUSH_NOTIFICATION_CONFIGS),
            Map.entry("tasks/pushNotification/delete", DELETE_TASK_PUSH_NOTIFICATION_CONFIG),
            Map.entry("tasks/pushNotificationConfig/set", CREATE_TASK_PUSH_NOTIFICATION_CONFIG),
            Map.entry("tasks/pushNotificationConfig/get", GET_TASK_PUSH_NOTIFICATION_CONFIG),
            Map.entry("tasks/pushNotificationConfig/list", LIST_TASK_PUSH_NOTIFICATION_CONFIGS),
            Map.entry("tasks/pushNotificationConfig/delete", DELETE_TASK_PUSH_NOTIFICATION_CONFIG),
            Map.entry("agent/authenticatedExtendedCard", GET_EXTENDED_AGENT_CARD)
    );

    /**
     * Resolve a wire method name to its canonical v1.0.0 form. Returns the
     * input unchanged if it isn't a known legacy alias.
     */
    public static String canonicalize(String method) {
        if (method == null) {
            return null;
        }
        return LEGACY_ALIASES.getOrDefault(method, method);
    }
}
