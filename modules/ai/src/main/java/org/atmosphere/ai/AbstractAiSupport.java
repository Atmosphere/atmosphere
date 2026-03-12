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

/**
 * Base class for {@link AiSupport} implementations that share a common
 * pattern: a volatile native client field, classpath-based availability
 * check, environment-driven lazy initialization, and delegation to a
 * framework-specific streaming method.
 *
 * <p>Subclasses provide:</p>
 * <ul>
 *   <li>{@link #nativeClientClassName()} — the FQCN to probe for availability</li>
 *   <li>{@link #createNativeClient(AiConfig.LlmSettings)} — factory for the native client</li>
 *   <li>{@link #doStream(Object, AiRequest, StreamingSession)} — the actual streaming logic</li>
 *   <li>{@link #clientDescription()} — human-readable name for error messages</li>
 * </ul>
 *
 * @param <C> the native client type (e.g., {@code ChatClient},
 *            {@code StreamingChatLanguageModel}, {@code Runner})
 */
public abstract class AbstractAiSupport<C> implements AiSupport {

    private volatile C nativeClient;

    /**
     * Returns the current native client instance, or {@code null} if not yet configured.
     */
    protected C getNativeClient() {
        return nativeClient;
    }

    /**
     * Sets the native client instance.
     *
     * @param client the native client
     */
    protected void setNativeClient(C client) {
        this.nativeClient = client;
    }

    /**
     * Returns the fully-qualified class name used to check whether this
     * adapter's dependencies are on the classpath.
     */
    protected abstract String nativeClientClassName();

    /**
     * Creates the native client from the given LLM settings. Called by
     * {@link #configure(AiConfig.LlmSettings)} when no client has been set yet.
     *
     * <p>Implementations should return {@code null} if the required provider
     * classes are not on the classpath or if the API key is missing.</p>
     *
     * @param settings the resolved LLM settings
     * @return the native client, or {@code null} if configuration is not possible
     */
    protected abstract C createNativeClient(AiConfig.LlmSettings settings);

    /**
     * Performs the actual streaming after the native client has been resolved.
     *
     * @param client  the non-null native client
     * @param request the AI request
     * @param session the streaming session
     */
    protected abstract void doStream(C client, AiRequest request, StreamingSession session);

    /**
     * Returns a human-readable description of the client type for error messages
     * (e.g., "ChatClient", "StreamingChatLanguageModel", "Runner").
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
    public void stream(AiRequest request, StreamingSession session) {
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
        doStream(client, request, session);
    }

    /**
     * Returns a hint message for the {@link IllegalStateException} thrown when
     * the native client is not configured. Override to provide adapter-specific
     * guidance.
     *
     * @return configuration hint message
     */
    protected String configurationHint() {
        return "Check your classpath and configuration.";
    }
}
