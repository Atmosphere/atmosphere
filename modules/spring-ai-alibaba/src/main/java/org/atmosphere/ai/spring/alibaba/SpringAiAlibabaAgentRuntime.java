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
 * ({@code ReactAgent.setSystemPrompt} threaded per-request, serialized on
 * the agent monitor to avoid singleton-mutation races),
 * {@link AiCapability#STRUCTURED_OUTPUT} (via the pipeline),
 * {@link AiCapability#CONVERSATION_MEMORY}, and
 * {@link AiCapability#PER_REQUEST_RETRY}. {@link AiCapability#TOKEN_USAGE}
 * is NOT declared — {@code ReactAgent.call} returns an
 * {@code AssistantMessage} that does not surface the underlying
 * {@code ChatResponse} usage metadata in v1.1.2.0; reading the agent's
 * {@code CompiledGraph} run state would be brittle across versions.
 * Tool calling is NOT declared in this first cut — Spring AI Alibaba's
 * tool surface bridges Spring AI {@code FunctionCallback}s, which would
 * need a separate {@code SpringAiAlibabaToolBridge} to satisfy
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

        var messages = new ArrayList<Message>();
        for (var chat : assembleMessages(context)) {
            messages.add(toSpringMessage(chat));
        }

        // Model-lifecycle hooks: same posture as Spring AI / LC4j / ADK / Koog
        // / Embabel / SK / AgentScope. Spring AI Alibaba is buffered (no
        // incremental token deltas), so onModelEnd fires once at completion
        // with the full duration but no TokenUsage (Alibaba's ReactAgent.call
        // returns AssistantMessage which has no usage surface in v1.1.2.0).
        var listeners = context.listeners();
        var modelName = context.model() != null ? context.model() : name();
        var startNanos = System.nanoTime();
        org.atmosphere.ai.AgentLifecycleListener.fireModelStart(
                listeners, modelName, messages.size(), context.tools().size());

        // Per-request RunnableConfig override: when the caller attached one
        // via SpringAiAlibabaRunnableConfig.attach(...), use the
        // call(messages, config) overload — that's the natural per-invocation
        // handle for thread continuation, checkpoint resume, stream mode,
        // metadata, and store. Without an override we fall back to the no-arg
        // agent.call(messages) — preserves prior behavior.
        var runnableConfig = SpringAiAlibabaRunnableConfig.from(context);
        AssistantMessage response;
        try {
            // Spring-managed ReactAgent is a singleton; ReactAgent.setSystemPrompt
            // mutates state on it. Serializing setSystemPrompt + call on the agent
            // monitor prevents two concurrent requests from clobbering each other's
            // system prompt. assembleMessages already places the system prompt as
            // the first SystemMessage in the messages list (defense in depth);
            // setSystemPrompt is kept because Alibaba's ReactAgent reads its own
            // field in addition to the input messages.
            synchronized (agent) {
                if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
                    try {
                        agent.setSystemPrompt(context.systemPrompt());
                    } catch (RuntimeException re) {
                        logger.trace(
                                "ReactAgent.setSystemPrompt threw — falling back to message-level system role",
                                re);
                    }
                }
                if (runnableConfig != null) {
                    logger.debug("Dispatching with per-request Alibaba RunnableConfig override");
                    response = agent.call(messages, runnableConfig);
                } else {
                    response = agent.call(messages);
                }
            }
        } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException gre) {
            // Boundary safety (Invariant #4): a checked framework failure
            // must surface through session.error and propagate so the
            // outer pipeline records the right lifecycle event.
            org.atmosphere.ai.AgentLifecycleListener.fireModelError(
                    listeners, modelName, gre);
            session.error(gre);
            throw new IllegalStateException("Spring AI Alibaba ReactAgent failed", gre);
        } catch (RuntimeException re) {
            // Mirror the checked-exception path for any unchecked failure
            // escaping ReactAgent.call so observers see the dispatch error
            // before propagation.
            org.atmosphere.ai.AgentLifecycleListener.fireModelError(
                    listeners, modelName, re);
            throw re;
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
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        org.atmosphere.ai.AgentLifecycleListener.fireModelEnd(
                listeners, modelName, null, durationMs);
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
                AiCapability.CONVERSATION_MEMORY,
                // Inherits AbstractAgentRuntime.executeWithOuterRetry — does
                // not override ownsPerRequestRetry(), so context.retryPolicy()
                // with maxRetries > 0 retries doExecute on pre-stream
                // RuntimeException. See modules/ai/README.md
                // "Per-Request Retry Architecture".
                AiCapability.PER_REQUEST_RETRY,
                // BUDGET_ENFORCEMENT: framework-level circuit breaker via the
                // AiPipeline BudgetCapturingSession decorator. Wall-clock
                // limits trip universally; token / step limits require the
                // runtime to emit TOKEN_USAGE through session.usage(), which
                // this runtime does NOT (see comment below). So callers
                // configuring a token-based AiBudget against this runtime
                // will see wall-clock breaches but not token breaches —
                // documented in modules/ai/README.md alongside the
                // capability matrix.
                AiCapability.BUDGET_ENFORCEMENT,
                // CONFIDENCE_SCORES: framework-level — AiPipeline's
                // ConfidenceCapturingSession parses the model-reported
                // confidence field on stream completion. Honest because
                // Alibaba honors SYSTEM_PROMPT and the buffered AssistantMessage
                // text reaches session.send before complete().
                AiCapability.CONFIDENCE_SCORES,
                // PASSIVATION: AgentPassivation snapshots context.history()
                // into a CheckpointStore. Honest because Alibaba threads
                // history into the Message list ReactAgent receives.
                AiCapability.PASSIVATION);
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
