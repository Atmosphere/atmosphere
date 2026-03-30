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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Interceptor that performs vector search over past conversations via
 * {@link ContextProvider} and injects relevant fragments into the system prompt.
 *
 * <p>Gracefully no-ops if no {@link ContextProvider} is available: logs once
 * at INFO level, then skips silently on subsequent calls.</p>
 */
public class SemanticRecallInterceptor implements AiInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SemanticRecallInterceptor.class);

    private final ContextProvider provider;
    private final int maxResults;
    private final AtomicBoolean warnedNoProvider = new AtomicBoolean(false);

    /**
     * @param provider   the context provider for vector search (may be null)
     * @param maxResults maximum number of past fragments to inject
     */
    public SemanticRecallInterceptor(ContextProvider provider, int maxResults) {
        this.provider = provider;
        this.maxResults = maxResults;
    }

    public SemanticRecallInterceptor(ContextProvider provider) {
        this(provider, 5);
    }

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        if (provider == null || !provider.isAvailable()) {
            if (warnedNoProvider.compareAndSet(false, true)) {
                logger.info("Semantic recall enabled but no ContextProvider available — skipping");
            }
            return request;
        }

        try {
            var query = provider.transformQuery(request.message());
            var docs = provider.retrieve(query, maxResults);
            var reranked = provider.rerank(query, docs);

            if (reranked.isEmpty()) {
                return request;
            }

            var context = reranked.stream()
                    .map(doc -> doc.content()
                            + (doc.source() != null ? " [Source: " + doc.source() + "]" : ""))
                    .collect(Collectors.joining("\n---\n"));

            var augmentedPrompt = (request.systemPrompt() != null ? request.systemPrompt() + "\n\n" : "")
                    + "Relevant context from past conversations:\n" + context;

            logger.debug("Injected {} semantic recall fragments", reranked.size());
            return request.withSystemPrompt(augmentedPrompt);
        } catch (Exception e) {
            logger.warn("Semantic recall failed: {}", e.getMessage());
            return request;
        }
    }

    @Override
    public void postProcess(AiRequest request, AtmosphereResource resource) {
        // no-op: recall is pre-process only
    }
}
