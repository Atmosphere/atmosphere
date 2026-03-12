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
package org.atmosphere.ai.rag;

import org.atmosphere.ai.ContextProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Zero-dependency {@link ContextProvider} that stores documents in memory
 * and uses simple word-overlap scoring for retrieval.
 *
 * <p>This provider is intended for development, testing, and small knowledge bases
 * where a full vector store is not needed. Documents are provided at construction
 * time and the instance is immutable and thread-safe.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * var provider = new InMemoryContextProvider(List.of(
 *     new ContextProvider.Document("Atmosphere supports WebSocket...", "docs/websocket.md", 1.0),
 *     new ContextProvider.Document("SSE transport enables...", "docs/sse.md", 1.0)
 * ));
 * var results = provider.retrieve("How does WebSocket work?", 3);
 * }</pre>
 */
public class InMemoryContextProvider implements ContextProvider {

    private final List<Document> documents;

    /**
     * Creates a provider with the given pre-loaded documents.
     *
     * @param documents the documents to search; defensively copied
     */
    public InMemoryContextProvider(List<Document> documents) {
        this.documents = List.copyOf(documents);
    }

    /**
     * Loads text files from the classpath and creates a provider.
     * Each file becomes a single document whose source is the resource path.
     *
     * @param resources classpath resource paths (e.g. "docs/websocket.md")
     * @return a new provider containing one document per resource
     * @throws IllegalArgumentException if a resource cannot be found or read
     */
    public static InMemoryContextProvider fromClasspath(String... resources) {
        var docs = new ArrayList<Document>();
        for (var resource : resources) {
            var content = readClasspathResource(resource);
            docs.add(new Document(content, resource, 1.0));
        }
        return new InMemoryContextProvider(docs);
    }

    @Override
    public List<Document> retrieve(String query, int maxResults) {
        if (query == null || query.isBlank() || documents.isEmpty() || maxResults <= 0) {
            return List.of();
        }

        var queryWords = tokenize(query);
        if (queryWords.isEmpty()) {
            return List.of();
        }

        record ScoredDoc(Document doc, double score) { }

        var scored = new ArrayList<ScoredDoc>();
        for (var doc : documents) {
            var docWords = tokenize(doc.content());
            double score = computeOverlapScore(queryWords, docWords);
            if (score > 0.0) {
                scored.add(new ScoredDoc(
                        new Document(doc.content(), doc.source(), score, doc.metadata()),
                        score));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());

        var results = new ArrayList<Document>();
        for (int i = 0; i < Math.min(maxResults, scored.size()); i++) {
            results.add(scored.get(i).doc());
        }
        return List.copyOf(results);
    }

    /**
     * Computes a word-overlap score between query and document tokens.
     * Returns a value between 0.0 and 1.0 representing the fraction of
     * query words found in the document.
     */
    private static double computeOverlapScore(Set<String> queryWords, Set<String> docWords) {
        int matches = 0;
        for (var word : queryWords) {
            if (docWords.contains(word)) {
                matches++;
            }
        }
        return (double) matches / queryWords.size();
    }

    /**
     * Tokenizes text into a set of lowercase words, stripping punctuation.
     */
    private static Set<String> tokenize(String text) {
        var words = text.toLowerCase(Locale.ROOT).split("[\\s\\p{Punct}]+");
        var result = new HashSet<>(Arrays.asList(words));
        result.remove("");
        return result;
    }

    private static String readClasspathResource(String resource) {
        var classLoader = InMemoryContextProvider.class.getClassLoader();
        try (var stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + resource);
            }
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read classpath resource: " + resource, e);
        }
    }
}
