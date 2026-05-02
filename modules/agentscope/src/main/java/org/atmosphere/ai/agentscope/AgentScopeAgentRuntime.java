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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * {@link org.atmosphere.ai.AgentRuntime} adapter for the AgentScope Java
 * framework (Tongyi Lab — {@code io.agentscope:agentscope-core}). Wraps a
 * {@link ReActAgent} so its native {@code Flux<Event>} streaming surface
 * forwards token deltas through Atmosphere's {@link StreamingSession}.
 *
 * <p>Auto-detected when {@code io.agentscope.core.ReActAgent} is on the
 * classpath. The agent itself is wired via Spring (
 * {@link AtmosphereAgentScopeAutoConfiguration}) — AgentScope models
 * (DashScope, OpenAI-compatible) need provider-specific construction that
 * can't be done from {@code llm.*} properties alone, so the runtime accepts
 * a user-built {@link ReActAgent} bean rather than building one itself.</p>
 *
 * <p><b>Capabilities:</b> {@link AiCapability#TEXT_STREAMING},
 * {@link AiCapability#SYSTEM_PROMPT}, {@link AiCapability#STRUCTURED_OUTPUT}
 * (via the pipeline), {@link AiCapability#CONVERSATION_MEMORY},
 * {@link AiCapability#TOKEN_USAGE}. Tool calling is intentionally NOT
 * declared in this first cut — AgentScope's {@code Toolkit} bridge into
 * Atmosphere's {@link org.atmosphere.ai.tool.ToolDefinition} surface is a
 * follow-up; declaring {@link AiCapability#TOOL_CALLING} without a real
 * bridge would violate Correctness Invariant #5 (Runtime Truth).</p>
 */
public class AgentScopeAgentRuntime extends AbstractAgentRuntime<ReActAgent> {

    private static final Logger logger = LoggerFactory.getLogger(AgentScopeAgentRuntime.class);

    private static volatile ReActAgent staticAgent;

    /**
     * Inject a pre-built {@link ReActAgent} from Spring auto-configuration.
     * Mirrors the {@code KoogAgentRuntime.setPromptExecutor} /
     * {@code LangChain4jAgentRuntime.setModel} pattern — the autoconfig
     * holds the Spring-managed agent bean and seeds the runtime statically
     * so the {@link java.util.ServiceLoader}-instantiated runtime picks it
     * up without needing constructor injection.
     */
    public static void setAgent(ReActAgent agent) {
        staticAgent = agent;
    }

    @Override
    public String name() {
        return "agentscope";
    }

    @Override
    protected String nativeClientClassName() {
        return "io.agentscope.core.ReActAgent";
    }

    @Override
    protected String clientDescription() {
        return "ReActAgent";
    }

    @Override
    protected String configurationHint() {
        return "Wire a ReActAgent @Bean (e.g. ReActAgent.builder().model(...).build()) "
                + "or call AgentScopeAgentRuntime.setAgent() before the first request.";
    }

    @Override
    protected ReActAgent createNativeClient(AiConfig.LlmSettings settings) {
        // AgentScope's Model surface (DashScope, OpenAI-compatible) needs
        // provider-specific construction the AiConfig.LlmSettings DTO can't
        // express. Defer to the user's @Bean wired via autoconfig.
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
    protected void doExecute(ReActAgent agent,
                             AgentExecutionContext context,
                             StreamingSession session) {
        var handle = doExecuteWithHandle(agent, context, session);
        try {
            handle.whenDone().get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ex) {
            // Already surfaced via session.error() in the subscribe handler.
        }
    }

    @Override
    protected ExecutionHandle doExecuteWithHandle(ReActAgent agent,
                                                  AgentExecutionContext context,
                                                  StreamingSession session) {
        admitThroughGateway(context);

        var msgs = new ArrayList<Msg>();
        for (var chat : assembleMessages(context)) {
            msgs.add(Msg.builder()
                    .role(toRole(chat.role()))
                    .textContent(chat.content())
                    .build());
        }

        var done = new CompletableFuture<Void>();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();

        Disposable subscription = agent.stream(msgs, StreamOptions.defaults())
                .subscribe(
                        event -> handleEvent(event, session),
                        error -> {
                            // Boundary safety (Invariant #4): every terminal
                            // path must close the session. error() is
                            // idempotent on Atmosphere's StreamingSession.
                            session.error(error);
                            done.completeExceptionally(error);
                        },
                        () -> {
                            if (!session.isClosed()) {
                                session.complete();
                            }
                            done.complete(null);
                        });

        return new ExecutionHandle() {
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    // AgentScope exposes interrupt() for cooperative cancel
                    // and the Reactor Disposable for unsubscription. Fire
                    // both: interrupt() unwinds the agent's internal loop;
                    // dispose() drops any in-flight emission to the session.
                    try {
                        agent.interrupt();
                    } catch (RuntimeException re) {
                        logger.trace("ReActAgent.interrupt() threw on cancel", re);
                    }
                    subscription.dispose();
                    if (!done.isDone()) {
                        done.completeExceptionally(new CancellationException("cancelled"));
                    }
                }
            }

            @Override
            public boolean isDone() {
                return done.isDone();
            }

            @Override
            public CompletableFuture<Void> whenDone() {
                return done;
            }
        };
    }

    private static void handleEvent(Event event, StreamingSession session) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        var msg = event.getMessage();
        var text = msg.getTextContent();
        if (text != null && !text.isEmpty()) {
            session.send(text);
        }
        // Token usage rides on the terminal Msg. Forwarding TOKEN_USAGE on
        // every chunk would double-count; AgentScope sets ChatUsage only on
        // the message that completes the round.
        var usage = msg.getChatUsage();
        if (usage != null && event.isLast()) {
            session.usage(new TokenUsage(
                    usage.getInputTokens(),
                    usage.getOutputTokens(),
                    0L,
                    usage.getTotalTokens(),
                    null));
        }
    }

    private static MsgRole toRole(String role) {
        if (role == null) {
            return MsgRole.USER;
        }
        return switch (role) {
            case "system" -> MsgRole.SYSTEM;
            case "assistant" -> MsgRole.ASSISTANT;
            case "tool" -> MsgRole.TOOL;
            default -> MsgRole.USER;
        };
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                // AiPipeline wraps the session in StructuredOutputCapturingSession
                // and augments the system prompt with schema instructions for
                // every SYSTEM_PROMPT-capable runtime — the contract test
                // pins this pairing.
                AiCapability.STRUCTURED_OUTPUT,
                // assembleMessages threads context.history() into the Msg
                // list the agent receives, so prior turns are honored.
                AiCapability.CONVERSATION_MEMORY,
                // handleEvent forwards Msg.getChatUsage() on isLast().
                AiCapability.TOKEN_USAGE);
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
