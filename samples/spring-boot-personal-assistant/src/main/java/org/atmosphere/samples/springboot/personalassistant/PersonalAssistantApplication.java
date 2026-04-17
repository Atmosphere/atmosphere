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
package org.atmosphere.samples.springboot.personalassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Personal assistant proof sample — demonstrates how the v0.5 foundation
 * primitives compose into a single, long-lived, memory-bearing agent that
 * runs on any of Atmosphere's seven hosted runtimes.
 *
 * <h2>What this sample exercises</h2>
 *
 * <ul>
 *   <li>{@code AgentState} — conversation history, durable facts (in
 *       {@code MEMORY.md}), hierarchical rules from the OpenClaw
 *       workspace</li>
 *   <li>{@code AgentWorkspace} — reads the shipped
 *       {@code .agent-workspace/} directory as an OpenClaw-compatible
 *       workspace</li>
 *   <li>{@code ProtocolBridge} — fleet members are dispatched over
 *       {@code InMemoryProtocolBridge}, the same abstraction as wire
 *       bridges would use for remote members</li>
 *   <li>{@code AgentIdentity} — per-user permission mode + audit trail</li>
 *   <li>{@code ToolExtensibilityPoint} — per-user MCP tools loaded from
 *       {@code .agent-workspace/MCP.md}</li>
 *   <li>{@code AiGateway} — every outbound LLM call traverses the
 *       gateway for rate-limiting, credential resolution, and tracing</li>
 * </ul>
 */
@SpringBootApplication
public class PersonalAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonalAssistantApplication.class, args);
    }
}
