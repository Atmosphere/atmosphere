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
package org.atmosphere.a2a;

import org.atmosphere.a2a.protocol.A2aMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class A2aMethodTest {

    @Test
    void sendMessage() {
        assertEquals("message/send", A2aMethod.SEND_MESSAGE);
    }

    @Test
    void sendStreamingMessage() {
        assertEquals("message/stream", A2aMethod.SEND_STREAMING_MESSAGE);
    }

    @Test
    void getTask() {
        assertEquals("tasks/get", A2aMethod.GET_TASK);
    }

    @Test
    void listTasks() {
        assertEquals("tasks/list", A2aMethod.LIST_TASKS);
    }

    @Test
    void cancelTask() {
        assertEquals("tasks/cancel", A2aMethod.CANCEL_TASK);
    }

    @Test
    void subscribeTask() {
        assertEquals("tasks/pushNotification/subscribe", A2aMethod.SUBSCRIBE_TASK);
    }

    @Test
    void unsubscribeTask() {
        assertEquals("tasks/pushNotification/unsubscribe", A2aMethod.UNSUBSCRIBE_TASK);
    }

    @Test
    void getPushNotification() {
        assertEquals("tasks/pushNotification/get", A2aMethod.GET_PUSH_NOTIFICATION);
    }

    @Test
    void listPushNotifications() {
        assertEquals("tasks/pushNotification/list", A2aMethod.LIST_PUSH_NOTIFICATIONS);
    }

    @Test
    void setPushNotification() {
        assertEquals("tasks/pushNotification/set", A2aMethod.SET_PUSH_NOTIFICATION);
    }

    @Test
    void getAgentCard() {
        assertEquals("agent/authenticatedExtendedCard", A2aMethod.GET_AGENT_CARD);
    }
}
