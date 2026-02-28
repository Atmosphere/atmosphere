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

import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * A {@link StreamingSession} wrapper that adds {@link #stream(String)} support.
 * Created by the {@code @AiEndpoint} infrastructure to enable the simple pattern:
 *
 * <pre>{@code
 * @Prompt
 * public void onPrompt(String message, StreamingSession session) {
 *     session.stream(message);
 * }
 * }</pre>
 *
 * <p>When {@code stream(message)} is called, this session:</p>
 * <ol>
 *   <li>Builds an {@link AiRequest} from the message + stored system prompt + model</li>
 *   <li>Runs {@link AiInterceptor#preProcess} in FIFO order</li>
 *   <li>Delegates to {@link AiSupport#stream(AiRequest, StreamingSession)}</li>
 *   <li>Runs {@link AiInterceptor#postProcess} in LIFO order</li>
 * </ol>
 */
public class AiStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(AiStreamingSession.class);

    private final StreamingSession delegate;
    private final AiSupport aiSupport;
    private final String systemPrompt;
    private final String model;
    private final List<AiInterceptor> interceptors;
    private final AtmosphereResource resource;

    /**
     * @param delegate     the underlying streaming session
     * @param aiSupport    the resolved AI support implementation
     * @param systemPrompt the system prompt from {@code @AiEndpoint}
     * @param model        the model name (may be null for provider default)
     * @param interceptors the interceptor chain
     * @param resource     the atmosphere resource for this client
     */
    public AiStreamingSession(StreamingSession delegate, AiSupport aiSupport,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource) {
        this.delegate = delegate;
        this.aiSupport = aiSupport;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.model = model;
        this.interceptors = interceptors != null ? interceptors : List.of();
        this.resource = resource;
    }

    @Override
    public void stream(String message) {
        var request = new AiRequest(message, systemPrompt, model, Map.of());

        // Pre-process: FIFO order
        for (var interceptor : interceptors) {
            try {
                request = interceptor.preProcess(request, resource);
            } catch (Exception e) {
                logger.error("AiInterceptor.preProcess failed: {}", interceptor.getClass().getName(), e);
                delegate.error(e);
                return;
            }
        }

        // Delegate to the AI support
        var finalRequest = request;
        try {
            aiSupport.stream(finalRequest, delegate);
        } finally {
            // Post-process: LIFO order (matching AtmosphereInterceptor convention)
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                try {
                    interceptors.get(i).postProcess(finalRequest, resource);
                } catch (Exception e) {
                    logger.error("AiInterceptor.postProcess failed: {}",
                            interceptors.get(i).getClass().getName(), e);
                }
            }
        }
    }

    // -- Delegate all StreamingSession methods --

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String token) {
        delegate.send(token);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
    }

    @Override
    public void complete() {
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        delegate.error(t);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    // visible for testing
    AiSupport aiSupport() {
        return aiSupport;
    }

    List<AiInterceptor> interceptors() {
        return interceptors;
    }

    String systemPrompt() {
        return systemPrompt;
    }
}
