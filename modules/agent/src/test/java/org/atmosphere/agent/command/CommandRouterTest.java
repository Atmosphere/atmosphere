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
}
