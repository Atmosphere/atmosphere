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
 * Marks a class as an Atmosphere Agent. An agent is a unified abstraction that
 * desugars to an AI endpoint with command routing and multi-protocol exposure
 * (A2A, MCP, AG-UI) based on classpath detection.
 *
 * <p>Everything is derived from the skill file or sensible defaults:</p>
 * <ul>
 *   <li>System prompt comes from the skill file verbatim</li>
 *   <li>Conversation memory is enabled by default</li>
 *   <li>{@code @Prompt} is optional — auto-generates {@code session.stream(message)}</li>
 *   <li>Protocol exposure is automatic based on classpath</li>
 * </ul>
 *
 * <p>Zero-code agent example:</p>
 * <pre>{@code
 * @Agent(name = "customer-support", skillFile = "skill.md")
 * public class SupportAgent {
 *     // Everything comes from skill.md + classpath + config
 * }
 * }</pre>
 *
 * <p>Agent with commands:</p>
 * <pre>{@code
 * @Agent(name = "devops", skillFile = "devops-skill.md")
 * public class DevOpsAgent {
 *
 *     @Command(value = "/status", description = "Show service status")
 *     public String status() {
 *         return "All systems operational.";
 *     }
 *
 *     @Command(value = "/deploy", description = "Deploy to staging",
 *              confirm = "Deploy latest build to staging?")
 *     public String deploy(String args) {
 *         return "Deployed " + args + " to staging.";
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Agent {

    /**
     * Agent name. Used in the registration path ({@code /atmosphere/agent/{name}})
     * and in protocol metadata (A2A Agent Card, MCP server name).
     */
    String name();

    /**
     * Classpath resource path to the skill file (typically {@code .md}).
     * The entire file becomes the system prompt verbatim. Sections within the
     * file are also extracted for protocol metadata:
     * <ul>
     *   <li>{@code ## Skills} — A2A Agent Card skills</li>
     *   <li>{@code ## Tools} — cross-referenced with {@code @AiTool} methods</li>
     *   <li>{@code ## Channels} — validated against classpath</li>
     *   <li>{@code ## Guardrails} — part of system prompt (LLM self-enforces)</li>
     * </ul>
     */
    String skillFile() default "";

    /**
     * Optional human-readable description of the agent. Used in protocol
     * metadata (A2A Agent Card description, MCP server description).
     */
    String description() default "";
}
