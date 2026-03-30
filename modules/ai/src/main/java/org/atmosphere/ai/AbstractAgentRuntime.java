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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for {@link AgentRuntime} implementations. Provides classpath-based
 * availability detection, lazy client initialization, and a template method
 * ({@link #doExecute}) for framework-specific execution.
 *
 * @param <C> the native client type (e.g. {@code ChatClient}, {@code StreamingChatModel})
 */
public abstract class AbstractAgentRuntime<C> implements AgentRuntime {

    private volatile C nativeClient;

    /** Returns the current native client instance, or {@code null} if not yet configured. */
    protected C getNativeClient() {
        return nativeClient;
    }

    /** Sets the native client instance. */
    protected void setNativeClient(C client) {
        this.nativeClient = client;
    }

    /**
     * Returns the fully-qualified class name used to check whether this
     * runtime's dependencies are on the classpath.
     */
    protected abstract String nativeClientClassName();

    /**
     * Creates the native client from the given LLM settings. Called by
     * {@link #configure(AiConfig.LlmSettings)} when no client has been set yet.
     *
     * @param settings the resolved LLM settings
     * @return the native client, or {@code null} if configuration is not possible
     */
    protected abstract C createNativeClient(AiConfig.LlmSettings settings);

    /**
     * Performs the actual agent execution after the native client has been resolved.
     *
     * @param client  the non-null native client
     * @param context the execution context
     * @param session the streaming session
     */
    protected abstract void doExecute(C client, AgentExecutionContext context,
                                      StreamingSession session);

    /**
     * Returns a human-readable description of the client type for error messages.
     */
    protected abstract String clientDescription();

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(nativeClientClassName());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (nativeClient != null) {
            return;
        }
        nativeClient = createNativeClient(settings);
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        var client = nativeClient;
        if (client == null) {
            var settings = AiConfig.get();
            if (settings == null) {
                settings = AiConfig.fromEnvironment();
            }
            configure(settings);
            client = nativeClient;
        }
        if (client == null) {
            throw new IllegalStateException(
                    name() + ": " + clientDescription() + " not configured. "
                            + configurationHint());
        }
        session.progress("Connecting to " + name() + "...");
        doExecute(client, context, session);
    }

    /**
     * Returns a hint message for the {@link IllegalStateException} thrown when
     * the native client is not configured.
     */
    protected String configurationHint() {
        return "Check your classpath and configuration.";
    }

    /**
     * Assemble the canonical message list from an execution context:
     * system prompt (if present) + conversation history + current user message.
     *
     * <p>Runtimes that need framework-specific message types can call this
     * method first and then translate each {@link ChatMessage} to their
     * native type.</p>
     *
     * @param context the execution context
     * @return an unmodifiable list of messages in conversation order
     */
    protected static List<ChatMessage> assembleMessages(AgentExecutionContext context) {
        var messages = new ArrayList<ChatMessage>();
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            messages.add(ChatMessage.system(context.systemPrompt()));
        }
        for (var h : context.history()) {
            messages.add(new ChatMessage(h.role(), h.content()));
        }
        messages.add(ChatMessage.user(context.message()));
        return List.copyOf(messages);
    }
}
