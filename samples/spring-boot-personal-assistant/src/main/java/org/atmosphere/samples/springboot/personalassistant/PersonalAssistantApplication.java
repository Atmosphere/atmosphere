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

import org.atmosphere.auth.TokenValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Personal assistant proof sample — demonstrates how the v0.5 foundation
 * primitives compose into a single, long-lived, memory-bearing agent that
 * runs on any of Atmosphere's contract-tested runtimes.
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

    /**
     * Resolves the demo sign-in token to the principal that long-term memory
     * keys on.
     *
     * <p>Long-term memory is per-user, so the sample needs a real identity on
     * the connection. The Console sends its {@code ?token=} as
     * {@code X-Atmosphere-Auth} on REST and as a query parameter on the
     * WebSocket, and {@code AiEndpointHandler.resolveRunOwner} defaults
     * {@code ai.userId} from the resolved {@code Principal}. That is the only
     * identity channel that actually reaches the endpoint.</p>
     *
     * <p>A {@code ?user=} page parameter does NOT: the Console reads only
     * {@code token} from the page URL and never forwards other query
     * parameters onto the transport, so every visitor collapsed onto one
     * bucket and each user was told the previous user's facts. Sign in as
     * {@code /?token=alice} and {@code /?token=bob} to see two separate
     * memories. Any non-blank token is accepted here because this is a demo;
     * a real deployment validates OIDC/JWT/mTLS and returns the verified
     * subject.</p>
     */
    @Bean
    public TokenValidator demoUserTokenValidator() {
        return token -> token == null || token.isBlank()
                ? new TokenValidator.Invalid("sign in with ?token=<user>")
                : new TokenValidator.Valid(token.trim());
    }

    public static void main(String[] args) {
        SpringApplication.run(PersonalAssistantApplication.class, args);
    }

    /**
     * Root path redirects to Atmosphere's bundled AI console — the same
     * WebSocket chat UI the spring-boot-ai-chat sample uses. The console
     * auto-wires to the first registered {@code @Coordinator} / {@code @Agent}
     * path, so it picks up {@code /atmosphere/agent/primary-assistant}
     * without extra configuration.
     */
    @Configuration
    static class ConsoleRedirect implements WebMvcConfigurer {
        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            registry.addRedirectViewController("/", "/atmosphere/console/");
        }
    }
}
