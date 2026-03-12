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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configures a {@link SimpleVectorStore} and loads the knowledge base documents
 * on startup.
 *
 * <p>The {@link EmbeddingModel} is auto-configured by Spring AI's OpenAI starter
 * when an API key is provided. Without it, the vector store bean is not created
 * and the sample falls back to demo mode.</p>
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreConfig.class);

    private static final String[] KNOWLEDGE_BASE = {
            "classpath:docs/atmosphere-overview.md",
            "classpath:docs/atmosphere-transports.md",
            "classpath:docs/atmosphere-ai-module.md",
            "classpath:docs/atmosphere-getting-started.md"
    };

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public VectorStore vectorStore(EmbeddingModel embeddingModel, ResourceLoader resourceLoader) {
        var store = SimpleVectorStore.builder(embeddingModel).build();

        var documents = loadDocuments(resourceLoader);
        if (!documents.isEmpty()) {
            store.doAdd(documents);
            logger.info("Loaded {} documents into SimpleVectorStore", documents.size());
        }

        return store;
    }

    private List<Document> loadDocuments(ResourceLoader resourceLoader) {
        var documents = new ArrayList<Document>();
        for (var path : KNOWLEDGE_BASE) {
            try {
                var resource = resourceLoader.getResource(path);
                if (!resource.exists()) {
                    logger.warn("Knowledge base file not found: {}", path);
                    continue;
                }
                var content = readResource(resource);
                var source = path.replace("classpath:", "");
                documents.add(new Document(content, Map.of("source", source)));
                logger.debug("Loaded knowledge base document: {}", source);
            } catch (IOException e) {
                logger.error("Failed to load knowledge base document: {}", path, e);
            }
        }
        return documents;
    }

    private static String readResource(org.springframework.core.io.Resource resource) throws IOException {
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
