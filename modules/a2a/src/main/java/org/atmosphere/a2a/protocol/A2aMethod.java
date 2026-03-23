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

/**
 * JSON-RPC method constants for the A2A (Agent-to-Agent) protocol.
 *
 * <p>Currently implemented methods: {@link #SEND_MESSAGE}, {@link #GET_TASK},
 * {@link #LIST_TASKS}, {@link #CANCEL_TASK}, {@link #GET_AGENT_CARD}.</p>
 *
 * <p>Streaming and push-notification methods are defined here to match the
 * A2A specification but are defined by the spec but unhandled by
 * {@code A2aProtocolHandler}. Invoking them will return a
 * {@code METHOD_NOT_FOUND} JSON-RPC error.</p>
 */
public final class A2aMethod {
    private A2aMethod() {
    }

    /** Send a message to the agent and receive a task. Implemented. */
    public static final String SEND_MESSAGE = "message/send";

    /** Stream a message to the agent. A2A spec constant; returns METHOD_NOT_FOUND. */
    public static final String SEND_STREAMING_MESSAGE = "message/stream";

    /** Get the current state of a task by ID. Implemented. */
    public static final String GET_TASK = "tasks/get";

    /** List tasks, optionally filtered by context ID. Implemented. */
    public static final String LIST_TASKS = "tasks/list";

    /** Cancel a running task by ID. Implemented. */
    public static final String CANCEL_TASK = "tasks/cancel";

    /** Subscribe to push notifications for a task. A2A spec constant; returns METHOD_NOT_FOUND. */
    public static final String SUBSCRIBE_TASK = "tasks/pushNotification/subscribe";

    /** Unsubscribe from push notifications for a task. A2A spec constant; returns METHOD_NOT_FOUND. */
    public static final String UNSUBSCRIBE_TASK = "tasks/pushNotification/unsubscribe";

    /** Get push notification config for a task. A2A spec constant; returns METHOD_NOT_FOUND. */
    public static final String GET_PUSH_NOTIFICATION = "tasks/pushNotification/get";

    /** List push notification configs. A2A spec constant; returns METHOD_NOT_FOUND. */
    public static final String LIST_PUSH_NOTIFICATIONS = "tasks/pushNotification/list";

    /** Set push notification config for a task. A2A spec constant; returns METHOD_NOT_FOUND. */
    public static final String SET_PUSH_NOTIFICATION = "tasks/pushNotification/set";

    /** Get the authenticated extended Agent Card. Implemented. */
    public static final String GET_AGENT_CARD = "agent/authenticatedExtendedCard";
}
