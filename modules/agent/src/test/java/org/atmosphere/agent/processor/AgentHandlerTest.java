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
package org.atmosphere.agent.processor;

import org.atmosphere.agent.annotation.Command;
import org.atmosphere.agent.command.CommandRegistry;
import org.atmosphere.agent.command.CommandResult;
import org.atmosphere.agent.command.CommandRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentHandler} — validates command routing integration
 * and LLM fallback behavior.
 */
public class AgentHandlerTest {

    static class TestHandlerAgent {
        @Command(value = "/ping", description = "Ping")
        public String ping() {
            return "pong";
        }

        @Command(value = "/greet", description = "Greet user")
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Command(value = "/danger", confirm = "Are you sure?")
        public String danger() {
            return "Dangerous action executed.";
        }
    }

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        var agent = new TestHandlerAgent();
        var registry = new CommandRegistry();
        registry.scan(TestHandlerAgent.class);
        router = new CommandRouter(registry, agent);
    }

    @Test
    public void testCommandRouting() {
        var result = router.route("client-1", "/ping");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("pong", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testCommandWithArgRouting() {
        var result = router.route("client-1", "/greet World");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Hello, World!", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testNonCommandFallsThrough() {
        var result = router.route("client-1", "What's the weather?");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testConfirmationFlow() {
        // First: confirmation required
        var result1 = router.route("client-1", "/danger");
        assertInstanceOf(CommandResult.ConfirmationRequired.class, result1);
        assertEquals("Are you sure?", ((CommandResult.ConfirmationRequired) result1).prompt());

        // Second: confirm
        var result2 = router.route("client-1", "yes");
        assertInstanceOf(CommandResult.Executed.class, result2);
        assertEquals("Dangerous action executed.", ((CommandResult.Executed) result2).response());
    }

    @Test
    public void testHelpCommand() {
        var result = router.route("client-1", "/help");
        assertInstanceOf(CommandResult.Executed.class, result);
        var help = ((CommandResult.Executed) result).response();
        assertTrue(help.contains("/ping"));
        assertTrue(help.contains("/greet"));
        assertTrue(help.contains("/danger"));
        assertTrue(help.contains("/help"));
    }

    @Test
    public void testUnknownCommandFallsThrough() {
        var result = router.route("client-1", "/nonexistent");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }
}
