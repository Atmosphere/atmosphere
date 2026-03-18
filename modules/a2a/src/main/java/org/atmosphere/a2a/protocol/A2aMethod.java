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

public final class A2aMethod {
    private A2aMethod() {
    }

    public static final String SEND_MESSAGE = "message/send";
    public static final String SEND_STREAMING_MESSAGE = "message/stream";
    public static final String GET_TASK = "tasks/get";
    public static final String LIST_TASKS = "tasks/list";
    public static final String CANCEL_TASK = "tasks/cancel";
    public static final String SUBSCRIBE_TASK = "tasks/pushNotification/subscribe";
    public static final String UNSUBSCRIBE_TASK = "tasks/pushNotification/unsubscribe";
    public static final String GET_PUSH_NOTIFICATION = "tasks/pushNotification/get";
    public static final String LIST_PUSH_NOTIFICATIONS = "tasks/pushNotification/list";
    public static final String SET_PUSH_NOTIFICATION = "tasks/pushNotification/set";
    public static final String GET_AGENT_CARD = "agent/authenticatedExtendedCard";
}
