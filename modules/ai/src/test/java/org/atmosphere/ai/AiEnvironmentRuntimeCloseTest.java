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
package org.atmosphere.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing an {@link AiEnvironment} closes the {@link AgentRuntime} instances it
 * loaded, and each releases only the native clients it built itself: never a
 * client the application bound, never one of another application, never a
 * runtime the application created by hand.
 */
// Resets the static AI facade (AiConfig) between tests: deprecated for
// framework code, kept for code that runs with no application.
@SuppressWarnings("deprecation")
class AiEnvironmentRuntimeCloseTest {

    @BeforeEach
    void setUp() {
        AiConfig.resetHostConfig();
        AiConfig.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        AiConfig.resetHostConfig();
        AiConfig.resetForTesting();
    }

    private static AiEnvironment application() {
        var environment = AiEnvironment.create(Map.<String, String>of()::get);
        CloseProbeAgentRuntime.enable(environment);
        return environment;
    }

    private static CloseProbeAgentRuntime probe(AiEnvironment environment) {
        for (var runtime : environment.runtimes().resolveAll()) {
            if (runtime instanceof CloseProbeAgentRuntime probe) {
                return probe;
            }
        }
        throw new AssertionError("the close probe is available in " + environment);
    }

    @Test
    void closingOneApplicationClosesOnlyTheNativeClientItsRuntimeBuilt() {
        var a = application();
        var b = application();
        try {
            var clientOfA = probe(a).client();
            var clientOfB = probe(b).client();
            assertNotSame(clientOfA, clientOfB, "each application's runtime builds its own client");

            a.close();
            a.close();

            assertEquals(1, clientOfA.closes(), "the closed application's built client is closed, once");
            assertEquals(0, clientOfB.closes(), "the running application's client is left alone");
        } finally {
            a.close();
            b.close();
            CloseProbeAgentRuntime.disable(a);
            CloseProbeAgentRuntime.disable(b);
        }
    }

    @Test
    void aRuntimeADroppedResolutionHandedOutIsClosedWithItsApplication() {
        var a = application();
        try {
            var before = probe(a);
            var clientBefore = before.client();
            a.runtimes().reset();
            var after = probe(a);
            assertNotSame(before, after, "the reset loaded a new instance");
            var clientAfter = after.client();

            a.close();

            assertEquals(1, clientBefore.closes(), "the instance the reset dropped, still referenced, is closed");
            assertEquals(1, clientAfter.closes());
        } finally {
            a.close();
            CloseProbeAgentRuntime.disable(a);
        }
    }

    @Test
    void aClientTheApplicationBoundIsNeverClosed() {
        var a = application();
        var bound = new CloseProbeAgentRuntime.Client();
        try {
            a.bindClient(CloseProbeAgentRuntime.class, CloseProbeAgentRuntime.Client.class, bound);
            assertSame(bound, probe(a).client(), "the runtime uses the bound client");

            a.close();

            assertEquals(0, bound.closes(), "a bound client belongs to the application");
        } finally {
            a.close();
            CloseProbeAgentRuntime.disable(a);
        }
    }

    @Test
    void aClientGivenToTheRuntimeIsNeverClosed() {
        var a = application();
        var given = new CloseProbeAgentRuntime.Client();
        try {
            probe(a).configureNativeClient(given);

            a.close();

            assertEquals(0, given.closes(), "a client handed to the runtime belongs to the caller");
        } finally {
            a.close();
            CloseProbeAgentRuntime.disable(a);
        }
    }

    @Test
    void aRuntimeTheApplicationCreatedIsNeverClosed() {
        var a = application();
        var own = new CloseProbeAgentRuntime();
        own.attach(a);
        try {
            var client = own.client();
            probe(a).client();

            a.close();

            assertEquals(0, client.closes(), "a runtime the application created is the application's to close");
            assertSame(client, own.client(), "it keeps its client");
        } finally {
            a.close();
            own.onEnvironmentClose();
            CloseProbeAgentRuntime.disable(a);
        }
    }

    @Test
    void aClosedRuntimeBuildsNoNewClient() {
        var a = application();
        try {
            var runtime = probe(a);

            a.close();

            var refused = assertThrows(IllegalStateException.class, runtime::client);
            assertEquals("close-probe is closed: its application stopped", refused.getMessage());
            assertThrows(IllegalStateException.class, () -> a.runtimes().resolveAll(),
                    "a closed application resolves no new runtime");
        } finally {
            a.close();
            CloseProbeAgentRuntime.disable(a);
        }
    }

    @Test
    void aClosedRuntimeDropsTheClientItBuiltAndFailsFast() {
        var a = application();
        try {
            var runtime = probe(a);
            var built = runtime.client();

            a.close();

            assertEquals(1, built.closes());
            var refused = assertThrows(IllegalStateException.class, runtime::client,
                    "a dispatch fails fast instead of reaching the closed client");
            assertEquals("close-probe is closed: its application stopped", refused.getMessage());
        } finally {
            a.close();
            CloseProbeAgentRuntime.disable(a);
        }
    }

    @Test
    void closingDoesNotWaitForAClientBeingBuiltAndClosesItOnceBuilt() throws Exception {
        var a = application();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var runtime = probe(a);
            CloseProbeAgentRuntime.blockCreate(a, entered, release);
            var building = CompletableFuture.supplyAsync(runtime::client);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the client is being built");

            var closing = CompletableFuture.runAsync(a::close);
            try {
                closing.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }

            var failure = assertThrows(ExecutionException.class, () -> building.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause(),
                    "the runtime closed while it built the client");
            var created = runtime.createdClients();
            assertEquals(1, created.size());
            assertEquals(1, created.getFirst().closes(), "the client built after the close is closed");
        } finally {
            release.countDown();
            a.close();
            CloseProbeAgentRuntime.disable(a);
        }
    }
}
