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
package org.atmosphere.interactions;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.resume.RunRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Coverage for detached background execution, cancellation, parity, and executor ownership. */
class BackgroundInteractionTest {

    private final InMemoryInteractionStore store = new InMemoryInteractionStore();

    private InteractionService service(Consumer<StreamingSession> script, ExecutorService executor,
                                       RunRegistry registry) {
        return new InteractionService(new ScriptedAgentRuntime(script), store, null,
                new InteractionStepMapper(), InteractionService.DEFAULT_MAX_STEPS,
                Duration.ofSeconds(5), Clock.systemUTC(), executor, registry);
    }

    private Interaction awaitStatus(String id, InteractionStatus status, long timeoutMs) {
        var deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (System.nanoTime() < deadline) {
            var loaded = store.load(id).orElse(null);
            if (loaded != null && loaded.status() == status) {
                return loaded;
            }
            sleep();
        }
        fail("interaction " + id + " did not reach " + status + " within " + timeoutMs + "ms");
        return null;
    }

    private void awaitSteps(String id, int atLeast, long timeoutMs) {
        var deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (System.nanoTime() < deadline) {
            var loaded = store.load(id).orElse(null);
            if (loaded != null && loaded.steps().size() >= atLeast) {
                return;
            }
            sleep();
        }
        fail("interaction " + id + " did not reach " + atLeast + " steps within " + timeoutMs + "ms");
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void backgroundRunIsRetrievableAfterCompletionAndAutoUnregisters() {
        var registry = new RunRegistry();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var svc = service(session -> {
                session.send("done");
                session.emit(new AiEvent.ToolStart("calc", Map.of()));
                session.complete();
            }, executor, registry);

            var launched = svc.createBackground(InteractionRequest.of("go"), "alice");
            assertEquals(InteractionStatus.RUNNING, launched.status(), "returns immediately as RUNNING");

            var done = awaitStatus(launched.id(), InteractionStatus.COMPLETED, 2000);
            assertEquals("done", done.finalText());
            assertEquals(1, done.steps().stream()
                    .filter(s -> InteractionStepMapper.TYPE_TOOL_CALL.equals(s.type())).count());
            assertTrue(registry.lookup(launched.id()).isEmpty(), "run auto-unregisters when done");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelMidRunPersistsCancelledWithCapturedSteps() throws InterruptedException {
        var registry = new RunRegistry();
        var executor = Executors.newSingleThreadExecutor();
        var gate = new CountDownLatch(1);
        try {
            var svc = service(session -> {
                session.emit(new AiEvent.ToolStart("t0", Map.of()));
                session.emit(new AiEvent.ToolStart("t1", Map.of()));
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                session.complete();
            }, executor, registry);

            var launched = svc.createBackground(InteractionRequest.of("go"), "alice");
            awaitSteps(launched.id(), 2, 2000);

            assertTrue(svc.cancel(launched.id(), "alice"), "owner can cancel an in-flight run");
            var cancelled = awaitStatus(launched.id(), InteractionStatus.CANCELLED, 2000);
            assertEquals(2, cancelled.steps().stream()
                    .filter(s -> InteractionStepMapper.TYPE_TOOL_CALL.equals(s.type())).count(),
                    "steps captured before cancel are retained");
            assertNull(cancelled.errorMessage(), "cancel is not an error");
        } finally {
            gate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelRejectedForNonOwner() {
        var registry = new RunRegistry();
        var executor = Executors.newSingleThreadExecutor();
        var gate = new CountDownLatch(1);
        try {
            var svc = service(session -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                session.complete();
            }, executor, registry);
            var launched = svc.createBackground(InteractionRequest.of("go"), "alice");
            assertFalse(svc.cancel(launched.id(), "bob"), "non-owner cannot cancel");
        } finally {
            gate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void syncAndBackgroundProduceIdenticalSteps() {
        Consumer<StreamingSession> script = session -> {
            session.emit(new AiEvent.TextDelta("Hel"));
            session.emit(new AiEvent.TextDelta("lo"));
            session.emit(new AiEvent.ToolStart("weather", Map.of("city", "Montreal")));
            session.emit(new AiEvent.ToolResult("weather", Map.of("temp", 22)));
            session.complete();
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var syncSvc = service(script, executor, new RunRegistry());
            var syncResult = syncSvc.create(InteractionRequest.of("hi"), new CollectingSession(), "alice");

            var bgSvc = service(script, executor, new RunRegistry());
            var launched = bgSvc.createBackground(InteractionRequest.of("hi"), "alice");
            var bgResult = awaitStatus(launched.id(), InteractionStatus.COMPLETED, 2000);

            assertEquals(projection(syncResult.steps()), projection(bgResult.steps()),
                    "sync and background capture identical durable steps");
            assertEquals(syncResult.finalText(), bgResult.finalText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stopShutsDownOwnedExecutorButNotBorrowed() {
        var borrowed = Executors.newSingleThreadExecutor();
        try {
            var withBorrowed = service(session -> session.complete(), borrowed, new RunRegistry());
            withBorrowed.stop();
            assertFalse(borrowed.isShutdown(), "borrowed executor must not be shut down");

            var owned = new InteractionService(new ScriptedAgentRuntime(s -> s.complete()), store);
            owned.stop();
            assertTrue(owned.executorForTest().isShutdown(), "owned executor is shut down on stop");
        } finally {
            borrowed.shutdownNow();
        }
    }

    private static List<String> projection(List<InteractionStep> steps) {
        return steps.stream().map(s -> s.type() + ":" + s.text() + ":" + s.toolName()).toList();
    }
}
