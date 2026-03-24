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
 * <p>Full-stack agent with WebSocket UI:</p>
 * <pre>{@code
 * @Agent(name = "devops", skillFile = "devops-skill.md")
 * public class DevOpsAgent {
 *
 *     @Prompt
 *     public void onMessage(String msg, StreamingSession session) {
 *         session.stream(msg);
 *     }
 *
 *     @Command(value = "/status", description = "Show service status")
 *     public String status() { return "All systems operational."; }
 * }
 * }</pre>
 *
 * <p>Headless agent (no WebSocket UI, A2A protocol only):</p>
 * <pre>{@code
 * @Agent(name = "research", endpoint = "/atmosphere/a2a/research",
 *        description = "Web research agent")
 * public class ResearchAgent {
 *
 *     @A2aSkill(id = "search", name = "Search", description = "Search the web")
 *     @A2aTaskHandler
 *     public void search(TaskContext task, @A2aParam(name="query") String query) {
 *         task.addArtifact(Artifact.text("Results for: " + query));
 *         task.complete("Search complete");
 *     }
 * }
 * }</pre>
 *
 * <p>Headless mode is auto-detected when a class has {@code @A2aSkill} methods
 * but no {@code @Prompt} method, or can be forced with {@code headless = true}.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Agent {

    /**
     * Agent name. Used in the registration path ({@code /atmosphere/agent/{name}})
     * and in protocol metadata (A2A Agent Card).
     */
    String name();

    /**
     * Classpath resource path to the skill file (typically {@code .md}).
     * The entire file becomes the system prompt verbatim. Sections within the
     * file are also extracted for protocol metadata:
     * <ul>
     *   <li>{@code ## Skills} — A2A Agent Card skills</li>
     *   <li>{@code ## Tools} — cross-referenced with {@code @AiTool} methods</li>
     *   <li>{@code ## Channels} — included in system prompt (routing validation planned)</li>
     *   <li>{@code ## Guardrails} — included in system prompt (LLM self-enforces)</li>
     * </ul>
     */
    String skillFile() default "";

    /**
     * Optional human-readable description of the agent. Used in protocol
     * metadata (A2A Agent Card description).
     */
    String description() default "";

    /**
     * Custom endpoint path for the agent's A2A protocol endpoint.
     * When non-empty, overrides the default {@code /atmosphere/agent/{name}/a2a}.
     * This is primarily useful for headless agents that only expose A2A.
     *
     * <p>Example: {@code endpoint = "/atmosphere/a2a/research"}</p>
     */
    String endpoint() default "";

    /**
     * Agent version, used in Agent Card metadata and protocol responses.
     */
    String version() default "1.0.0";

    /**
     * When {@code true}, no WebSocket UI handler is registered — the agent
     * operates as a headless A2A/MCP service only.
     *
     * <p>Headless mode is also auto-detected: if the class has
     * {@code @A2aSkill}/{@code @A2aTaskHandler} methods but no
     * {@code @Prompt} method, it is treated as headless.</p>
     */
    boolean headless() default false;
}
