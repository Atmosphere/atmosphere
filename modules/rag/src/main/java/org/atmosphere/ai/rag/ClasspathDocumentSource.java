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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link DocumentSource} that loads bundled classpath resources (e.g. a
 * {@code docs/} folder shipped in the jar). Zero-dep. A {@code "classpath:"}
 * prefix on a resource name is tolerated and stripped.
 */
public final class ClasspathDocumentSource implements DocumentSource {

    private final ClassLoader loader;
    private final List<String> resources;

    public ClasspathDocumentSource(String... resources) {
        this(Thread.currentThread().getContextClassLoader(), resources);
    }

    public ClasspathDocumentSource(ClassLoader loader, String... resources) {
        this.loader = loader;
        this.resources = List.of(resources);
    }

    @Override
    public List<ContextProvider.Document> load() {
        var docs = new ArrayList<ContextProvider.Document>(resources.size());
        for (var resource : resources) {
            docs.add(new ContextProvider.Document(read(resource), resource, 1.0,
                    Map.of("source_path", resource, "source_type", "classpath")));
        }
        return List.copyOf(docs);
    }

    @Override
    public String name() {
        return "classpath:" + resources;
    }

    private String read(String resource) {
        var name = resource.startsWith("classpath:") ? resource.substring("classpath:".length()) : resource;
        var cl = loader != null ? loader : getClass().getClassLoader();
        try (var in = cl.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read classpath resource " + resource, e);
        }
    }
}
