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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the sealed {@link CommandResult} interface.
 */
public class CommandResultTest {

    @Test
    public void testExecuted() {
        var result = new CommandResult.Executed("hello");
        assertEquals("hello", result.response());
        assertInstanceOf(CommandResult.Executed.class, result);
    }

    @Test
    public void testConfirmationRequired() {
        var result = new CommandResult.ConfirmationRequired("Are you sure?");
        assertEquals("Are you sure?", result.prompt());
        assertInstanceOf(CommandResult.ConfirmationRequired.class, result);
    }

    @Test
    public void testNotACommand() {
        var result = new CommandResult.NotACommand();
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testSealedSwitch() {
        CommandResult result = new CommandResult.Executed("test");
        var matched = switch (result) {
            case CommandResult.Executed e -> "executed: " + e.response();
            case CommandResult.ConfirmationRequired c -> "confirm: " + c.prompt();
            case CommandResult.NotACommand n -> "not a command";
        };
        assertEquals("executed: test", matched);
    }

    @Test
    public void testExecutedEquality() {
        var a = new CommandResult.Executed("test");
        var b = new CommandResult.Executed("test");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testConfirmationRequiredEquality() {
        var a = new CommandResult.ConfirmationRequired("sure?");
        var b = new CommandResult.ConfirmationRequired("sure?");
        assertEquals(a, b);
    }
}
