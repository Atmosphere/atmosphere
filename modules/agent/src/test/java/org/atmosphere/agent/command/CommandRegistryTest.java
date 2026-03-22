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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CommandRegistryTest {

    // -- Test fixtures --

    static class ValidAgent {
        @Command(value = "/status", description = "Show status")
        public String status() {
            return "OK";
        }

        @Command(value = "/deploy", description = "Deploy service", confirm = "Really deploy?")
        public String deploy(String args) {
            return "Deployed " + args;
        }
    }

    static class NoCommandAgent {
        public String notACommand() {
            return "nope";
        }
    }

    static class BadReturnType {
        @Command("/bad")
        public int badReturn() {
            return 42;
        }
    }

    static class BadParamType {
        @Command("/bad")
        public String badParam(int count) {
            return String.valueOf(count);
        }
    }

    static class TooManyParams {
        @Command("/bad")
        public String tooMany(String a, String b) {
            return a + b;
        }
    }

    static class MissingSlash {
        @Command("help")
        public String help() {
            return "help";
        }
    }

    static class DuplicatePrefix {
        @Command("/dup")
        public String first() {
            return "first";
        }

        @Command("/dup")
        public String second() {
            return "second";
        }
    }

    // -- Tests --

    @Test
    public void testScanFindsCommands() {
        var registry = new CommandRegistry();
        registry.scan(ValidAgent.class);
        assertEquals(2, registry.size());
    }

    @Test
    public void testLookupByPrefix() {
        var registry = new CommandRegistry();
        registry.scan(ValidAgent.class);

        var status = registry.lookup("/status");
        assertTrue(status.isPresent());
        assertEquals("/status", status.get().prefix());
        assertEquals("Show status", status.get().description());
        assertEquals("", status.get().confirm());
        assertEquals(CommandRegistry.ParamType.NONE, status.get().paramType());
    }

    @Test
    public void testLookupCommandWithArgs() {
        var registry = new CommandRegistry();
        registry.scan(ValidAgent.class);

        var deploy = registry.lookup("/deploy");
        assertTrue(deploy.isPresent());
        assertEquals(CommandRegistry.ParamType.STRING, deploy.get().paramType());
        assertEquals("Really deploy?", deploy.get().confirm());
    }

    @Test
    public void testLookupUnknownPrefix() {
        var registry = new CommandRegistry();
        registry.scan(ValidAgent.class);
        assertTrue(registry.lookup("/unknown").isEmpty());
    }

    @Test
    public void testNoCommands() {
        var registry = new CommandRegistry();
        registry.scan(NoCommandAgent.class);
        assertEquals(0, registry.size());
    }

    @Test
    public void testBadReturnTypeThrows() {
        var registry = new CommandRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.scan(BadReturnType.class));
    }

    @Test
    public void testBadParamTypeThrows() {
        var registry = new CommandRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.scan(BadParamType.class));
    }

    @Test
    public void testTooManyParamsThrows() {
        var registry = new CommandRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.scan(TooManyParams.class));
    }

    @Test
    public void testMissingSlashThrows() {
        var registry = new CommandRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.scan(MissingSlash.class));
    }

    @Test
    public void testDuplicatePrefixThrows() {
        var registry = new CommandRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.scan(DuplicatePrefix.class));
    }

    @Test
    public void testGenerateHelp() {
        var registry = new CommandRegistry();
        registry.scan(ValidAgent.class);

        var help = registry.generateHelp();
        assertTrue(help.contains("/status"));
        assertTrue(help.contains("Show status"));
        assertTrue(help.contains("/deploy"));
        assertTrue(help.contains("/help"));
    }

    @Test
    public void testGenerateHelpEmpty() {
        var registry = new CommandRegistry();
        assertEquals("No commands available.", registry.generateHelp());
    }

    @Test
    public void testAllCommands() {
        var registry = new CommandRegistry();
        registry.scan(ValidAgent.class);

        var all = registry.allCommands();
        assertEquals(2, all.size());
    }
}
