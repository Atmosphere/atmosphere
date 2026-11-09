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
package org.atmosphere.integrationtests.a2ainterop;

import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interoperability with the official A2A Java SDK: its JSON-RPC client, with no
 * Atmosphere code on the client side, resolves the agent card an Atmosphere
 * agent publishes and drives the agent through the A2A v1.0 methods. The SDK
 * is a test dependency only; Atmosphere's A2A server does not use it.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class A2aSdkInteropIntegrationTest {

    private static final String ENDPOINT = "/atmosphere/a2a/interop";
    private static final long WAIT_SECONDS = 10;

    private EmbeddedAtmosphereServer server;
    private AgentCard card;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.a2ainterop")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.agent.processor");
        server.start();
        card = A2A.getAgentCard(server.getBaseUrl() + ENDPOINT);
    }

    @AfterAll
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void theSdkResolvesTheAgentCard() {
        assertEquals("interop", card.name());
        assertTrue(card.skills().stream().anyMatch(s -> "echo".equals(s.id())), card.skills().toString());
        var jsonRpc = card.supportedInterfaces().stream()
                .filter(i -> "JSONRPC".equals(i.protocolBinding()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JSONRPC interface: " + card.supportedInterfaces()));
        assertEquals(server.getBaseUrl() + ENDPOINT, jsonRpc.url());
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void aBlockingSendReturnsTheCompletedTaskWhichGetAndListThenFind() throws Exception {
        var events = new CompletableFuture<Task>();
        try (var client = client(false, (event, c) -> completeWithTask(events, event))) {
            client.sendMessage(A2A.toUserMessage("hello"));
            var task = events.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());
            assertEquals("echo: hello", text(task));

            var fetched = client.getTask(new TaskQueryParams(task.id()));
            assertEquals(task.id(), fetched.id());
            assertEquals(TaskState.TASK_STATE_COMPLETED, fetched.status().state());
            assertEquals("echo: hello", text(fetched));

            var listed = client.listTasks(ListTasksParams.builder().contextId(task.contextId()).build());
            assertTrue(listed.tasks().stream().anyMatch(t -> task.id().equals(t.id())), listed.tasks().toString());

            var later = client.listTasks(ListTasksParams.builder().contextId(task.contextId())
                    .statusTimestampAfter(Instant.now().plus(Duration.ofHours(1))).build());
            assertTrue(later.tasks().isEmpty(), later.tasks().toString());
        }
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void aStreamingSendDeliversEventsEndingInTheCompletedTask() throws Exception {
        var received = new CopyOnWriteArrayList<ClientEvent>();
        var done = new CompletableFuture<Task>();
        BiConsumer<ClientEvent, AgentCard> consumer = (event, c) -> {
            received.add(event);
            var task = taskOf(event);
            if (task != null && task.status().state().isFinal()) {
                done.complete(task);
            }
        };
        try (var client = client(true, consumer)) {
            client.sendMessage(A2A.toUserMessage("streamed"));
            var task = done.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());
            assertEquals("echo: streamed", text(task));
            assertFalse(received.isEmpty());
        }
    }

    @Test
    public void anUnknownTaskIsAnErrorTheSdkReports() throws Exception {
        try (var client = client(false, (event, c) -> { })) {
            assertThrows(A2AClientException.class,
                    () -> client.getTask(new TaskQueryParams("no-such-task")));
        }
    }

    private Client client(boolean streaming, BiConsumer<ClientEvent, AgentCard> consumer)
            throws A2AClientException {
        return Client.builder(card)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                .clientConfig(new ClientConfig.Builder().setStreaming(streaming).build())
                .addConsumer(consumer)
                .build();
    }

    private static void completeWithTask(CompletableFuture<Task> future, ClientEvent event) {
        var task = taskOf(event);
        if (task != null) {
            future.complete(task);
        }
    }

    private static Task taskOf(ClientEvent event) {
        return switch (event) {
            case TaskEvent e -> e.getTask();
            case TaskUpdateEvent e -> e.getTask();
            default -> null;
        };
    }

    private static String text(Task task) {
        assertNotNull(task.artifacts(), "the task carries no artifacts");
        var parts = task.artifacts().stream().flatMap(a -> a.parts().stream()).toList();
        return parts.stream()
                .filter(TextPart.class::isInstance)
                .map(p -> ((TextPart) p).text())
                .reduce((a, b) -> a + b)
                .orElseThrow(() -> new AssertionError("no text part in " + List.copyOf(parts)));
    }
}
