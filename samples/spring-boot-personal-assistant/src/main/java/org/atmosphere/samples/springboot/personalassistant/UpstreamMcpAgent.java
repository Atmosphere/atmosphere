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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outbound-MCP demonstration endpoint within the personal-assistant sample.
 *
 * <p>The {@link PrimaryAssistant @Coordinator} crew above this class
 * demonstrates the local-tool path: each crew member's skill is invoked
 * via {@code @AiTool} and dispatched through {@code AgentFleet}. This
 * endpoint demonstrates the complementary path — tools that <em>do not
 * live in this JVM</em>, but on a remote MCP server consumed through
 * {@code atmosphere-mcp-client}. {@link McpToolsInterceptor} augments
 * every {@link org.atmosphere.ai.AiRequest} with the remote tools so the
 * runtime treats them identically to local {@code @AiTool} methods —
 * the same approval policy, observability, and retry semantics apply.</p>
 *
 * <p>This closes the parity gap with
 * <a href="https://platform.claude.com/docs/en/managed-agents/overview">Anthropic's
 * Claude Managed Agents</a>, which lists MCP servers as a first-class
 * field on the Agent definition and wires remote MCP tools in by default.
 * In Atmosphere the same outcome is achieved by declaring an
 * {@link org.atmosphere.mcp.client.McpToolSource} bean (see
 * {@link RemoteToolsConfig}) and surfacing its tools via an
 * {@link org.atmosphere.ai.AiInterceptor}.</p>
 *
 * <p>This endpoint also carries the assistant's <b>long-term memory</b> —
 * with zero wiring in this sample. It is a plain {@link AiEndpoint} that opts
 * into the deep-agent harness via the app-wide
 * {@code atmosphere.ai.deep-agent.enabled} flag (set in {@code application.yml}):
 * the flag makes the framework attach a
 * {@link org.atmosphere.ai.memory.LongTermMemoryInterceptor} and enable
 * conversation memory (plus a conservative prompt-cache default), so stored
 * user facts are recalled into the system prompt before each turn and new
 * facts are extracted when the session closes and the assistant remembers a
 * user across reconnects. (Before the harness existed this took three sample
 * classes and a static holder — see the sample README's history note.)</p>
 *
 * <p><b>Pair with {@code spring-boot-mcp-server} as the upstream:</b></p>
 * <pre>{@code
 * # Terminal 1: start the upstream MCP server (port 8083)
 * ./mvnw spring-boot:run -pl samples/spring-boot-mcp-server
 *
 * # Terminal 2: start this sample (port 8080)
 * ./mvnw spring-boot:run -pl samples/spring-boot-personal-assistant
 *
 * # Browser
 * open http://localhost:8080/atmosphere/console/
 * # WebSocket endpoint for the outbound-MCP demo:
 * #   ws://localhost:8080/atmosphere/personal-assistant/upstream-tools
 * }</pre>
 */
@AiEndpoint(path = "/atmosphere/personal-assistant/upstream-tools",
        systemPrompt = "You are a helpful assistant connected to a remote MCP server "
                + "that exposes the upstream chat server's tools. The available tools are: "
                + "atmosphere_version (always works — returns the framework version and "
                + "runtime info), and the chat-state tools list_users, ban_user, "
                + "broadcast_message, send_message which require active chat clients on the "
                + "upstream and will return 'No chat broadcaster active' when none are "
                + "connected. When the user asks something general about the integration "
                + "(e.g. 'is this working', 'what version', 'show me the integration'), "
                + "prefer atmosphere_version. Only call chat-state tools when the user "
                + "specifically asks about chat users or messages. Never invent users, "
                + "versions, or values — only report what the tools return.",
        interceptors = {McpToolsInterceptor.class})
@AgentScope(unrestricted = true,
        justification = "Outbound-MCP demo endpoint — accepts arbitrary prompts to exercise remote tool dispatch.")
public class UpstreamMcpAgent {

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamMcpAgent.class);

    /**
     * Establish the demo identity at connection time. Long-term memory is keyed
     * by user (so facts never leak between people), which means recall — and
     * the on-close fact extraction — need a stable identity that outlives a
     * single turn; an anonymous connection deliberately gets no memory. A real
     * app sets {@code ai.userId} from its auth stack (a {@code Principal} or an
     * {@code AtmosphereInterceptor}); this demo derives it from a {@code ?user=}
     * query param, set here in {@code @Ready} so it persists for the whole
     * connection (both the per-turn recall and the disconnect extraction read
     * it). Two browser tabs with the same {@code ?user=} share memory; different
     * users are isolated; absent, it defaults to {@code "demo-user"}.
     */
    @Ready
    public void onReady(AtmosphereResource resource) {
        var user = resource.getRequest().getParameter("user");
        resource.getRequest().setAttribute("ai.userId",
                user != null && !user.isBlank() ? user : "demo-user");
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        LOG.info("Upstream-MCP agent prompt from {}: {}", resource.uuid(), message);
        session.stream(message);
    }
}
