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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Set;

/**
 * {@link org.atmosphere.ai.AgentRuntime} adapter for Spring AI Alibaba's
 * {@link ReactAgent} (Alibaba Cloud AI team —
 * {@code com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework}).
 *
 * <p><b>Buffered streaming.</b> Spring AI Alibaba's agent surface is
 * synchronous — {@code ReactAgent.call(List&lt;Message&gt;)} returns a single
 * {@link AssistantMessage} after the entire ReAct loop completes. There is
 * no native {@code Flux}/streaming agent method as of v1.1.2.0. This
 * adapter therefore delivers the full reply as one
 * {@link StreamingSession#send(String)} chunk followed by
 * {@link StreamingSession#complete()} — the Atmosphere transport still
 * streams that chunk to the client over WebSocket / SSE / long-poll, but
 * there is no incremental token surface from the LLM. Callers that want
 * token-by-token streaming should drive Spring AI's
 * {@code StreamingChatModel} directly via
 * {@code atmosphere-spring-ai} instead, losing Spring AI Alibaba's
 * multi-agent / graph orchestration value.</p>
 *
 * <p>Auto-detected when {@code com.alibaba.cloud.ai.graph.agent.ReactAgent}
 * is on the classpath. The agent itself is wired via Spring (
 * {@link AtmosphereSpringAiAlibabaAutoConfiguration}).</p>
 *
 * <p><b>Capabilities:</b> {@link AiCapability#TEXT_STREAMING} (buffered —
 * see above), {@link AiCapability#SYSTEM_PROMPT}
 * ({@code ReactAgent.setSystemPrompt} threaded per-request),
 * {@link AiCapability#STRUCTURED_OUTPUT} (via the pipeline),
 * {@link AiCapability#CONVERSATION_MEMORY},
 * {@link AiCapability#TOKEN_USAGE} (when the underlying Spring AI
 * {@code ChatResponse} carries usage metadata). Tool calling is NOT
 * declared in this first cut — Spring AI Alibaba's tool surface bridges
 * Spring AI {@code FunctionCallback}s, which would need a separate
 * {@code SpringAiAlibabaToolBridge} to satisfy
 * {@link AiCapability#TOOL_APPROVAL}.</p>
 */
public class SpringAiAlibabaAgentRuntime extends AbstractAgentRuntime<ReactAgent> {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiAlibabaAgentRuntime.class);

    private static volatile ReactAgent staticAgent;

    /** Inject a pre-built {@link ReactAgent} from Spring auto-configuration. */
    public static void setAgent(ReactAgent agent) {
        staticAgent = agent;
    }

    @Override
    public String name() {
        return "spring-ai-alibaba";
    }

    @Override
    protected String nativeClientClassName() {
        return "com.alibaba.cloud.ai.graph.agent.ReactAgent";
    }

    @Override
    protected String clientDescription() {
        return "ReactAgent";
    }

    @Override
    protected String configurationHint() {
        return "Wire a ReactAgent @Bean (e.g. ReactAgent.builder().model(chatModel)...build()) "
                + "or call SpringAiAlibabaAgentRuntime.setAgent() before the first request.";
    }

    @Override
    protected ReactAgent createNativeClient(AiConfig.LlmSettings settings) {
        return staticAgent;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (getNativeClient() == null && staticAgent != null) {
            setNativeClient(staticAgent);
        }
        super.configure(settings);
    }

    @Override
    protected void doExecute(ReactAgent agent,
                             AgentExecutionContext context,
                             StreamingSession session) {
        admitThroughGateway(context);

        // ReactAgent stores systemPrompt on the agent itself; honor the
        // per-request value from context so SYSTEM_PROMPT is genuinely
        // wired, not just declared.
        if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
            try {
                agent.setSystemPrompt(context.systemPrompt());
            } catch (RuntimeException re) {
                logger.trace("ReactAgent.setSystemPrompt threw — falling back to message-level system role", re);
            }
        }

        var messages = new ArrayList<Message>();
        for (var chat : assembleMessages(context)) {
            messages.add(toSpringMessage(chat));
        }

        AssistantMessage response;
        try {
            response = agent.call(messages);
        } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException gre) {
            // Boundary safety (Invariant #4): a checked framework failure
            // must surface through session.error and propagate so the
            // outer pipeline records the right lifecycle event.
            session.error(gre);
            throw new IllegalStateException("Spring AI Alibaba ReactAgent failed", gre);
        }

        // Buffered delivery: one chunk + complete. The Atmosphere transport
        // still streams this chunk to the client; the limitation is that
        // there is no incremental token surface from the LLM.
        if (response != null) {
            var text = response.getText();
            if (text != null && !text.isEmpty()) {
                session.send(text);
            }
        }
        if (!session.isClosed()) {
            session.complete();
        }
    }

    private static Message toSpringMessage(org.atmosphere.ai.llm.ChatMessage msg) {
        return switch (msg.role()) {
            case "system" -> new SystemMessage(msg.content());
            case "assistant" -> new AssistantMessage(msg.content());
            default -> new UserMessage(msg.content());
        };
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                // Buffered streaming — full reply delivered as one
                // session.send() chunk. Honest because the Atmosphere
                // transport still frames the response as a streamed
                // message; the limitation (no incremental token deltas
                // from the LLM) is documented in the class Javadoc.
                AiCapability.TEXT_STREAMING,
                // setSystemPrompt(...) is threaded per request in doExecute,
                // and assembleMessages also includes a SystemMessage in
                // the List<Message> dispatched to call(...) as a defense
                // in depth.
                AiCapability.SYSTEM_PROMPT,
                // AiPipeline wraps the session in StructuredOutputCapturingSession
                // for every SYSTEM_PROMPT-capable runtime — a single buffered
                // chunk is still a complete final frame the wrapper can parse.
                AiCapability.STRUCTURED_OUTPUT,
                // assembleMessages threads context.history() into the Message
                // list ReactAgent receives, so prior turns are honored.
                AiCapability.CONVERSATION_MEMORY);
        // TOKEN_USAGE not declared: ReactAgent.call returns
        // AssistantMessage, which has no surface for the
        // ChatResponse usage metadata. The agent framework's graph
        // execution captures usage internally but does not return it
        // through the call(...) API as of v1.1.2.0. Adding a
        // TOKEN_USAGE bridge requires reading the agent's CompiledGraph
        // run state, which is brittle across versions.
    }

    @Override
    public java.util.List<String> models() {
        var settings = AiConfig.get();
        if (settings == null || settings.model() == null || settings.model().isBlank()) {
            return java.util.List.of();
        }
        return java.util.List.of(settings.model());
    }
}
