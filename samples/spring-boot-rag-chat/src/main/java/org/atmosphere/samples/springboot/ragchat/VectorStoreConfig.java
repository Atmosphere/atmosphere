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
package org.atmosphere.samples.springboot.ragchat;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.rag.RagChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

/**
 * Loads the knowledge base documents into both the in-memory {@link KnowledgeBase}
 * (always available, used by {@code @AiTool} methods) and the Spring AI
 * {@link SimpleVectorStore} (when embeddings are configured).
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreConfig.class);

    private static final String[] KNOWLEDGE_BASE = {
            "classpath:docs/atmosphere-overview.md",
            "classpath:docs/atmosphere-transports.md",
            "classpath:docs/atmosphere-ai-module.md",
            "classpath:docs/atmosphere-getting-started.md",
            "classpath:docs/atmosphere-agents.md"
    };

    private final ResourceLoader resourceLoader;

    public VectorStoreConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Always populate the in-memory knowledge base — used by {@code @AiTool}
     * methods for explicit search, even in demo mode without embeddings.
     */
    @jakarta.annotation.PostConstruct
    void populateKnowledgeBase() {
        var docs = new ArrayList<ContextProvider.Document>();
        for (var path : KNOWLEDGE_BASE) {
            try {
                var resource = resourceLoader.getResource(path);
                if (!resource.exists()) {
                    logger.warn("Knowledge base file not found: {}", path);
                    continue;
                }
                var content = readResource(resource);
                var source = path.replace("classpath:", "");
                docs.add(new ContextProvider.Document(content, source, 1.0));
                logger.debug("Loaded knowledge base document: {}", source);
            } catch (IOException e) {
                logger.error("Failed to load knowledge base document: {}", path, e);
            }
        }
        KnowledgeBase.instance().addDocuments(docs);
        logger.info("Knowledge base: {} documents loaded", docs.size());
    }

    /**
     * Builds the Spring AI vector store and ingests the chunked knowledge base.
     *
     * <p>Gated on the {@code atmosphere.ai.vector-store.enabled} <em>property</em>, not on
     * {@code @ConditionalOnBean(EmbeddingModel.class)}. That distinction is the bug this
     * sample shipped until 4.0.67: {@code @ConditionalOnBean} is only contractually reliable
     * inside auto-configuration classes. This is a user {@code @Configuration}, and Spring
     * parses user configurations <em>before</em> auto-configurations (which arrive via
     * {@code DeferredImportSelector}), so {@code EmbeddingModel} — produced by
     * {@code OpenAiEmbeddingAutoConfiguration} — was never in the registry when the condition
     * was evaluated. The condition was therefore <em>always</em> false and the store was never
     * created, on any configuration. A property condition is evaluated against the
     * {@code Environment}, which is fully populated at parse time, so it behaves correctly
     * here; the {@code EmbeddingModel} argument is resolved later, at bean-creation time,
     * once every definition is registered.</p>
     *
     * <p><strong>Opt-in, and deliberately so.</strong> Declaring a {@link VectorStore} bean makes
     * {@code AtmosphereRagAutoConfiguration} wire {@code SpringAiVectorStoreContextProvider} as
     * the retrieval source. Without a reachable embedding endpoint the store stays empty, that
     * provider returns nothing, and the keyless demo path loses the word-overlap context it
     * would otherwise inject — the sample silently gets *worse* than having no store at all.
     * So the store is created only when an operator has actually pointed the sample at an
     * embedding endpoint and set {@code atmosphere.ai.vector-store.enabled=true}; see the
     * README's Ollama recipe. Enabling it without a working endpoint is the one configuration
     * this sample cannot make useful, which is why it is not the default.</p>
     *
     * <p>Ingestion is best-effort even then: a failure is logged and leaves an empty store
     * rather than aborting startup.</p>
     */
    @Bean
    @ConditionalOnProperty(name = "atmosphere.ai.vector-store.enabled", havingValue = "true")
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        var store = SimpleVectorStore.builder(embeddingModel).build();

        var springDocs = RagChunker.chunkAll(KnowledgeBase.instance().documents()).stream()
                .map(d -> {
                    var metadata = new java.util.HashMap<String, Object>();
                    metadata.putAll(d.metadata());
                    metadata.put("source", d.source());
                    return new Document(d.content(), Map.copyOf(metadata));
                })
                .toList();
        if (!springDocs.isEmpty()) {
            try {
                store.add(springDocs);
                logger.info("Loaded {} chunks into SimpleVectorStore with embeddings", springDocs.size());
            } catch (RuntimeException e) {
                logger.warn("Embedding the knowledge base failed ({}); retrieval will return no "
                        + "results. Point spring.ai.openai.base-url at a reachable embedding "
                        + "endpoint (e.g. Ollama with nomic-embed-text) to enable semantic search.",
                        e.toString());
            }
        }

        return store;
    }

    private static String readResource(Resource resource) throws IOException {
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
