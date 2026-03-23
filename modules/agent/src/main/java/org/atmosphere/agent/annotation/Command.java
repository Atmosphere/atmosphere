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
package org.atmosphere.agent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a slash command within an {@link Agent}-annotated class.
 * Commands are routed before the LLM pipeline — messages starting with the
 * command prefix bypass AI and execute the method directly.
 *
 * <p>The annotated method must return {@code String} and accept one of:</p>
 * <ul>
 *   <li>No parameters — {@code String status()}</li>
 *   <li>{@code String} — the arguments after the command prefix</li>
 * </ul>
 *
 * <p>Commands are routed in the web console via "/" prefix routing.
 * When {@code atmosphere-channels} is on the classpath, commands are
 * automatically routed to all configured channels (Slack, Telegram, etc.)
 * via the {@code ChannelAiBridge} integration — no custom bridge needed.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Command(value = "/deploy", description = "Deploy to staging",
 *          confirm = "Deploy latest build to staging?")
 * public String deploy(String args) {
 *     return "Deployed " + args + " to staging.";
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Command {

    /**
     * The command prefix. Must start with "/".
     */
    String value();

    /**
     * Optional human-readable description. Shown in auto-generated {@code /help}
     * and in protocol metadata (MCP tool description, A2A skill description).
     */
    String description() default "";

    /**
     * Optional confirmation prompt for destructive actions. When non-empty,
     * the command router sends this prompt to the user and waits for "yes"/"y"
     * before executing the command. Pending confirmations expire after 60 seconds.
     */
    String confirm() default "";
}
