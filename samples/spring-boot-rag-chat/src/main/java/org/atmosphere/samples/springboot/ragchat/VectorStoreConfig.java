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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
     * When embeddings are available, also load documents into a vector store
     * for semantic similarity search via the framework's RAG pipeline.
     */
    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        var store = SimpleVectorStore.builder(embeddingModel).build();

        var springDocs = KnowledgeBase.instance().documents().stream()
                .map(d -> new Document(d.content(), Map.of("source", d.source())))
                .toList();
        if (!springDocs.isEmpty()) {
            store.doAdd(springDocs);
            logger.info("Loaded {} documents into SimpleVectorStore with embeddings",
                    springDocs.size());
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
