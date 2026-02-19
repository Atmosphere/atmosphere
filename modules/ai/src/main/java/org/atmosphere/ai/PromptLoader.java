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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt text from classpath resources (typically {@code .md} files).
 * Results are cached so each resource is read only once.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * String prompt = PromptLoader.load("prompts/system-prompt.md");
 * }</pre>
 */
public final class PromptLoader {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptLoader() {
    }

    /**
     * Loads the text content of a classpath resource and caches the result.
     *
     * @param resourcePath classpath resource path (e.g. {@code "prompts/system-prompt.md"})
     * @return the trimmed text content
     * @throws UncheckedIOException   if the resource cannot be read
     * @throws IllegalArgumentException if the resource is not found
     */
    public static String load(String resourcePath) {
        return CACHE.computeIfAbsent(resourcePath, PromptLoader::readResource);
    }

    /**
     * Clears the prompt cache. Primarily intended for testing.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    private static String readResource(String path) {
        var classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PromptLoader.class.getClassLoader();
        }
        try (var stream = classLoader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("Prompt resource not found on classpath: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt resource: " + path, e);
        }
    }
}
