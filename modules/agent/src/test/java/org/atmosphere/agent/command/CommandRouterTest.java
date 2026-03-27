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
package org.atmosphere.agent.command;

import org.atmosphere.agent.annotation.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CommandRouterTest {

    private TestAgent agent;
    private CommandRouter router;

    static class TestAgent {
        @Command(value = "/status", description = "Show status")
        public String status() {
            return "All systems operational.";
        }

        @Command(value = "/echo", description = "Echo args")
        public String echo(String args) {
            return "Echo: " + args;
        }

        @Command(value = "/deploy", description = "Deploy", confirm = "Really deploy?")
        public String deploy(String args) {
            return "Deployed " + args;
        }

        @Command(value = "/reset", confirm = "Reset all data?")
        public String reset() {
            return "Data reset.";
        }
    }

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
        var registry = new CommandRegistry();
        registry.scan(TestAgent.class);
        router = new CommandRouter(registry, agent);
    }

    @Test
    public void testSimpleCommand() {
        var result = router.route("client-1", "/status");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("All systems operational.", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testCommandWithArgs() {
        var result = router.route("client-1", "/echo hello world");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Echo: hello world", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testCommandNoArgs() {
        var result = router.route("client-1", "/echo");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Echo: ", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testConfirmationRequired() {
        var result = router.route("client-1", "/deploy v2.1");
        assertInstanceOf(CommandResult.ConfirmationRequired.class, result);
        assertEquals("Really deploy?", ((CommandResult.ConfirmationRequired) result).prompt());
    }

    @Test
    public void testConfirmationThenExecute() {
        router.route("client-1", "/deploy v2.1");
        var result = router.route("client-1", "yes");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Deployed v2.1", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testConfirmationWithY() {
        router.route("client-1", "/deploy v2.1");
        var result = router.route("client-1", "y");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Deployed v2.1", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testConfirmationDenied() {
        router.route("client-1", "/deploy v2.1");
        var result = router.route("client-1", "no");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Command cancelled.", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testConfirmationDeniedWithCancel() {
        router.route("client-1", "/deploy v2.1");
        var result = router.route("client-1", "cancel");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Command cancelled.", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testConfirmationNoParams() {
        var result = router.route("client-1", "/reset");
        assertInstanceOf(CommandResult.ConfirmationRequired.class, result);
        router.route("client-1", "yes");
    }

    @Test
    public void testConfirmationPerClient() {
        router.route("client-1", "/deploy v1");
        router.route("client-2", "/deploy v2");

        var r1 = router.route("client-1", "yes");
        assertInstanceOf(CommandResult.Executed.class, r1);
        assertEquals("Deployed v1", ((CommandResult.Executed) r1).response());

        var r2 = router.route("client-2", "yes");
        assertInstanceOf(CommandResult.Executed.class, r2);
        assertEquals("Deployed v2", ((CommandResult.Executed) r2).response());
    }

    @Test
    public void testYesWithoutPendingFallsThrough() {
        var result = router.route("client-1", "yes");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testNoWithoutPendingFallsThrough() {
        var result = router.route("client-1", "no");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testUnknownCommandFallsThrough() {
        var result = router.route("client-1", "/unknown");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testNonCommandFallsThrough() {
        var result = router.route("client-1", "What is the weather?");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testHelpCommand() {
        var result = router.route("client-1", "/help");
        assertInstanceOf(CommandResult.Executed.class, result);
        var help = ((CommandResult.Executed) result).response();
        assertTrue(help.contains("/status"));
        assertTrue(help.contains("/echo"));
        assertTrue(help.contains("/deploy"));
    }

    @Test
    public void testNullMessage() {
        var result = router.route("client-1", null);
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testBlankMessage() {
        var result = router.route("client-1", "  ");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testNewConfirmationOverridesPrevious() {
        router.route("client-1", "/deploy v1");
        router.route("client-1", "/deploy v2");

        // Confirming should execute v2, not v1
        var result = router.route("client-1", "yes");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Deployed v2", ((CommandResult.Executed) result).response());
    }

    // -- Concurrency tests --

    @Test
    public void testConcurrentDifferentClients() throws Exception {
        int threadCount = 20;
        var barrier = new CyclicBarrier(threadCount);
        var successes = new AtomicInteger();
        var threads = new ArrayList<Thread>();

        for (int i = 0; i < threadCount; i++) {
            var clientId = "concurrent-client-" + i;
            threads.add(Thread.startVirtualThread(() -> {
                try {
                    barrier.await();
                    var r = router.route(clientId, "/status");
                    if (r instanceof CommandResult.Executed e
                            && "All systems operational.".equals(e.response())) {
                        successes.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Thread failed: " + e.getMessage());
                }
            }));
        }
        for (var t : threads) {
            t.join(5000);
        }
        assertEquals(threadCount, successes.get());
    }

    @Test
    public void testConcurrentConfirmationSameClient() throws Exception {
        // Start a confirmation flow
        router.route("race-client", "/deploy v1");

        int threadCount = 10;
        var barrier = new CyclicBarrier(threadCount);
        var executed = new AtomicInteger();
        var notACommand = new AtomicInteger();
        var threads = new ArrayList<Thread>();

        // All threads try to confirm simultaneously
        for (int i = 0; i < threadCount; i++) {
            threads.add(Thread.startVirtualThread(() -> {
                try {
                    barrier.await();
                    var r = router.route("race-client", "yes");
                    if (r instanceof CommandResult.Executed) {
                        executed.incrementAndGet();
                    } else if (r instanceof CommandResult.NotACommand) {
                        notACommand.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Thread failed: " + e.getMessage());
                }
            }));
        }
        for (var t : threads) {
            t.join(5000);
        }

        // Exactly one thread wins the confirmation, rest fall through
        assertEquals(1, executed.get(),
                "Exactly one thread should execute the confirmed command");
        assertEquals(threadCount - 1, notACommand.get(),
                "Other threads should fall through as NotACommand");
    }

    @Test
    public void testConcurrentCommandAndConfirmation() throws Exception {
        // Client has a pending /deploy v1
        router.route("interleave-client", "/deploy v1");

        var barrier = new CyclicBarrier(2);
        var results = new CommandResult[2];

        // Thread 1: confirm "yes"
        var t1 = Thread.startVirtualThread(() -> {
            try {
                barrier.await();
                results[0] = router.route("interleave-client", "yes");
            } catch (Exception e) {
                fail(e);
            }
        });

        // Thread 2: new command /deploy v2
        var t2 = Thread.startVirtualThread(() -> {
            try {
                barrier.await();
                results[1] = router.route("interleave-client", "/deploy v2");
            } catch (Exception e) {
                fail(e);
            }
        });

        t1.join(5000);
        t2.join(5000);

        // Per-client lock ensures serialization — no exceptions, no corruption
        assertNotNull(results[0]);
        assertNotNull(results[1]);
    }

    @Test
    public void testHighContentionManyClientsWithConfirmation() throws Exception {
        int clientCount = 50;
        var barrier = new CyclicBarrier(clientCount);
        var successes = new AtomicInteger();
        var threads = new ArrayList<Thread>();

        for (int i = 0; i < clientCount; i++) {
            var clientId = "contention-" + i;
            threads.add(Thread.startVirtualThread(() -> {
                try {
                    barrier.await();
                    var r1 = router.route(clientId, "/deploy build-" + clientId);
                    assertInstanceOf(CommandResult.ConfirmationRequired.class, r1);
                    var r2 = router.route(clientId, "yes");
                    if (r2 instanceof CommandResult.Executed e
                            && e.response().startsWith("Deployed")) {
                        successes.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Thread failed: " + e.getMessage());
                }
            }));
        }
        for (var t : threads) {
            t.join(10000);
        }
        assertEquals(clientCount, successes.get(),
                "All clients should complete their confirmation flow independently");
    }
}
