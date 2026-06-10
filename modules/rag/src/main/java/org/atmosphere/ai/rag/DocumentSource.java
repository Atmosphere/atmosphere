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

import java.util.List;

/**
 * The <em>Extract</em> step of the RAG pipeline: a source that loads raw
 * documents to be chunked ({@link RagChunker}) and indexed ({@link InMemoryContextProvider}
 * or a vector store). The retrieval + chunking layers already ship; this is the
 * loader abstraction that was missing.
 *
 * <p>Two zero-dependency implementations ship: {@link FileSystemDocumentSource}
 * (walk a directory) and {@link ClasspathDocumentSource} (read bundled
 * resources). For heavier extraction (PDF, HTML, OCR) implement this SPI over a
 * Spring AI {@code DocumentReader} or a LangChain4j {@code DocumentParser}.</p>
 */
@FunctionalInterface
public interface DocumentSource {

    /**
     * Load all documents from this source.
     *
     * @return one {@link ContextProvider.Document} per source document, with
     *         {@code source} set to a stable identifier (relative path / resource
     *         name) and {@code score} defaulted to {@code 1.0}
     */
    List<ContextProvider.Document> load();

    /** Short descriptive name for logging / diagnostics. */
    default String name() {
        return getClass().getSimpleName();
    }
}
