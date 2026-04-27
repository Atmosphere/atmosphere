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
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies v1.0.0 PascalCase method names + pre-1.0 alias canonicalization. */
class A2aMethodTest {

    @Test
    void v1MethodNamesArePascalCase() {
        assertEquals("SendMessage", A2aMethod.SEND_MESSAGE);
        assertEquals("SendStreamingMessage", A2aMethod.SEND_STREAMING_MESSAGE);
        assertEquals("GetTask", A2aMethod.GET_TASK);
        assertEquals("ListTasks", A2aMethod.LIST_TASKS);
        assertEquals("CancelTask", A2aMethod.CANCEL_TASK);
        assertEquals("SubscribeToTask", A2aMethod.SUBSCRIBE_TO_TASK);
        assertEquals("CreateTaskPushNotificationConfig",
                A2aMethod.CREATE_TASK_PUSH_NOTIFICATION_CONFIG);
        assertEquals("GetTaskPushNotificationConfig",
                A2aMethod.GET_TASK_PUSH_NOTIFICATION_CONFIG);
        assertEquals("ListTaskPushNotificationConfigs",
                A2aMethod.LIST_TASK_PUSH_NOTIFICATION_CONFIGS);
        assertEquals("DeleteTaskPushNotificationConfig",
                A2aMethod.DELETE_TASK_PUSH_NOTIFICATION_CONFIG);
        assertEquals("GetExtendedAgentCard", A2aMethod.GET_EXTENDED_AGENT_CARD);
    }

    @Test
    void canonicalizeReturnsV1NameForLegacySlash() {
        assertEquals("SendMessage", A2aMethod.canonicalize("message/send"));
        assertEquals("SendStreamingMessage", A2aMethod.canonicalize("message/stream"));
        assertEquals("GetTask", A2aMethod.canonicalize("tasks/get"));
        assertEquals("ListTasks", A2aMethod.canonicalize("tasks/list"));
        assertEquals("CancelTask", A2aMethod.canonicalize("tasks/cancel"));
        assertEquals("SubscribeToTask", A2aMethod.canonicalize("tasks/resubscribe"));
        assertEquals("GetExtendedAgentCard",
                A2aMethod.canonicalize("agent/authenticatedExtendedCard"));
    }

    @Test
    void canonicalizeMapsBothPushNotificationPathsToCrudMethods() {
        // pre-1.0 used `tasks/pushNotification/*` and `tasks/pushNotificationConfig/*`
        assertEquals("CreateTaskPushNotificationConfig",
                A2aMethod.canonicalize("tasks/pushNotification/set"));
        assertEquals("CreateTaskPushNotificationConfig",
                A2aMethod.canonicalize("tasks/pushNotificationConfig/set"));
        assertEquals("GetTaskPushNotificationConfig",
                A2aMethod.canonicalize("tasks/pushNotification/get"));
        assertEquals("ListTaskPushNotificationConfigs",
                A2aMethod.canonicalize("tasks/pushNotification/list"));
        assertEquals("DeleteTaskPushNotificationConfig",
                A2aMethod.canonicalize("tasks/pushNotification/delete"));
    }

    @Test
    void canonicalizePassesThroughV1Names() {
        assertEquals("SendMessage", A2aMethod.canonicalize("SendMessage"));
        assertEquals("UnknownMethod", A2aMethod.canonicalize("UnknownMethod"));
    }

    @Test
    void canonicalizeNullReturnsNull() {
        assertNull(A2aMethod.canonicalize(null));
    }
}
