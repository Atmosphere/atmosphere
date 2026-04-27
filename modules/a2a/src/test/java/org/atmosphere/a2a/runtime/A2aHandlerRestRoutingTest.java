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
package org.atmosphere.a2a.runtime;

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the v1.0.0 HTTP+JSON / REST binding (colon-verb URLs) translates
 * to the right JSON-RPC method names. Path mapping uses spec §5.3.
 */
class A2aHandlerRestRoutingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private A2aHandler handler;

    @BeforeEach
    void setUp() {
        var registry = new A2aRegistry();
        var taskManager = new TaskManager();
        var card = registry.buildAgentCard("agent", "test", "1.0", "/a2a");
        var protocolHandler = new A2aProtocolHandler(registry, taskManager, card, card);
        handler = new A2aHandler(protocolHandler);
    }

    private String methodFromRest(String path, String verb, String body) throws Exception {
        var rpc = handler.restRoute(path, verb, body);
        if (rpc == null) {
            return null;
        }
        return mapper.readTree(rpc).get("method").stringValue();
    }

    @Test
    void postMessageSendRoutesToSendMessage() throws Exception {
        assertEquals("SendMessage",
                methodFromRest("/atmosphere/a2a/agent/message:send", "POST", "{}"));
    }

    @Test
    void postMessageStreamRoutesToSendStreamingMessage() throws Exception {
        assertEquals("SendStreamingMessage",
                methodFromRest("/atmosphere/a2a/agent/message:stream", "POST", "{}"));
    }

    @Test
    void getTaskByIdRoutesToGetTask() throws Exception {
        assertEquals("GetTask",
                methodFromRest("/atmosphere/a2a/agent/tasks/abc-123", "GET", ""));
    }

    @Test
    void getTasksRoutesToListTasks() throws Exception {
        assertEquals("ListTasks",
                methodFromRest("/atmosphere/a2a/agent/tasks", "GET", ""));
    }

    @Test
    void postTaskCancelColonVerb() throws Exception {
        assertEquals("CancelTask",
                methodFromRest("/atmosphere/a2a/agent/tasks/abc-123:cancel", "POST", "{}"));
    }

    @Test
    void postTaskSubscribeColonVerb() throws Exception {
        assertEquals("SubscribeToTask",
                methodFromRest("/atmosphere/a2a/agent/tasks/abc-123:subscribe", "POST", "{}"));
    }

    @Test
    void postPushNotificationConfigsRoutesToCreate() throws Exception {
        assertEquals("CreateTaskPushNotificationConfig",
                methodFromRest("/atmosphere/a2a/agent/tasks/t1/pushNotificationConfigs",
                        "POST", "{\"url\":\"https://hook\"}"));
    }

    @Test
    void getPushNotificationConfigsRoutesToList() throws Exception {
        assertEquals("ListTaskPushNotificationConfigs",
                methodFromRest("/atmosphere/a2a/agent/tasks/t1/pushNotificationConfigs",
                        "GET", ""));
    }

    @Test
    void getSinglePushNotificationConfigRoutesToGet() throws Exception {
        assertEquals("GetTaskPushNotificationConfig",
                methodFromRest("/atmosphere/a2a/agent/tasks/t1/pushNotificationConfigs/c1",
                        "GET", ""));
    }

    @Test
    void deletePushNotificationConfigRoutesToDelete() throws Exception {
        assertEquals("DeleteTaskPushNotificationConfig",
                methodFromRest("/atmosphere/a2a/agent/tasks/t1/pushNotificationConfigs/c1",
                        "DELETE", ""));
    }

    @Test
    void getExtendedAgentCardRoute() throws Exception {
        assertEquals("GetExtendedAgentCard",
                methodFromRest("/atmosphere/a2a/agent/extendedAgentCard", "GET", ""));
    }

    @Test
    void unknownPathReturnsNull() {
        assertNull(handler.restRoute("/atmosphere/something/else", "GET", ""));
    }
}
