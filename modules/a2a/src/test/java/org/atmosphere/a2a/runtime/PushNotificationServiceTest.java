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

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.a2a.types.TaskPushNotificationConfig;
import org.atmosphere.a2a.types.TaskState;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushNotificationServiceTest {

    @Test
    void crudRoundTrips() {
        var tm = new TaskManager();
        try (var svc = new PushNotificationService(tm)) {
            var stored = svc.create(new TaskPushNotificationConfig("t1", "https://example.com/hook"));
            assertNotNull(stored.id(), "an id is assigned when the client omits one");
            assertEquals(stored, svc.get("t1", stored.id()));
            assertEquals(1, svc.list("t1").size());
            assertTrue(svc.delete("t1", stored.id()));
            assertNull(svc.get("t1", stored.id()));
            assertFalse(svc.delete("t1", stored.id()), "deleting twice reports nothing removed");
        }
    }

    @Test
    void rejectsNonHttpOrRelativeUrl() {
        var tm = new TaskManager();
        try (var svc = new PushNotificationService(tm)) {
            assertThrows(IllegalArgumentException.class,
                    () -> svc.create(new TaskPushNotificationConfig("t1", "ftp://evil/hook")));
            assertThrows(IllegalArgumentException.class,
                    () -> svc.create(new TaskPushNotificationConfig("t1", "/relative/path")));
            assertThrows(IllegalArgumentException.class,
                    () -> svc.create(new TaskPushNotificationConfig("t1", "")));
        }
    }

    @Test
    void boundedConfigsPerTask() {
        var tm = new TaskManager();
        try (var svc = new PushNotificationService(tm)) {
            for (var i = 0; i < PushNotificationService.MAX_CONFIGS_PER_TASK; i++) {
                svc.create(new TaskPushNotificationConfig(null, "id-" + i, "t1", "https://h/" + i, null, null));
            }
            assertThrows(IllegalStateException.class,
                    () -> svc.create(new TaskPushNotificationConfig(
                            null, "overflow", "t1", "https://h/x", null, null)),
                    "the per-task config cap is enforced (Backpressure)");
        }
    }

    @Test
    void deliversOnTerminalStateWithToken() throws Exception {
        var received = new CountDownLatch(1);
        var body = new AtomicReference<String>();
        var token = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst(PushNotificationService.TOKEN_HEADER));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            var tm = new TaskManager();
            try (var svc = new PushNotificationService(tm)) {
                var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
                var task = tm.createTask("ctx-1");
                svc.create(new TaskPushNotificationConfig(
                        null, null, task.taskId(), url, "secret-token", null));

                task.complete("all done"); // terminal transition fires delivery

                assertTrue(received.await(5, TimeUnit.SECONDS),
                        "the webhook received the terminal notification");
                assertEquals("secret-token", token.get(), "the validation token is forwarded");
                assertTrue(body.get().contains(task.taskId()), "the payload carries the task id");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonTerminalStateDoesNotDeliver() throws Exception {
        var delivered = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            delivered.countDown();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var tm = new TaskManager();
            try (var svc = new PushNotificationService(tm)) {
                var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
                var task = tm.createTask("ctx-1");
                svc.create(new TaskPushNotificationConfig(null, null, task.taskId(), url, null, null));

                task.updateStatus(TaskState.WORKING, "still going");

                assertFalse(delivered.await(1, TimeUnit.SECONDS),
                        "a non-terminal status must not trigger a webhook");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closeUnregistersListenerAndDeliversNothing() throws Exception {
        var delivered = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            delivered.countDown();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var tm = new TaskManager();
            var svc = new PushNotificationService(tm);
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            var task = tm.createTask("ctx-1");
            svc.create(new TaskPushNotificationConfig(null, null, task.taskId(), url, null, null));

            svc.close(); // unregisters the task listener and clears configs

            task.complete("done");
            assertFalse(delivered.await(1, TimeUnit.SECONDS),
                    "a closed service must deliver nothing (listener removed)");
        } finally {
            server.stop(0);
        }
    }
}
