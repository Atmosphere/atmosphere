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

/**
 * Result of routing a message through the {@link CommandRouter}.
 */
public sealed interface CommandResult {

    /**
     * The command was executed successfully.
     *
     * @param response the command's return value
     */
    record Executed(String response) implements CommandResult {
    }

    /**
     * The command requires user confirmation before execution.
     *
     * @param prompt the confirmation prompt to display
     */
    record ConfirmationRequired(String prompt) implements CommandResult {
    }

    /**
     * The message is not a command — should fall through to the LLM pipeline.
     */
    record NotACommand() implements CommandResult {
    }
}
