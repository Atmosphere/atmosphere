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
 * Resolves a provider API key from the ambient process configuration so that
 * every {@link org.atmosphere.ai.AgentRuntime} adapter can obtain the
 * framework-resolved key without inventing its own {@code <provider>.api.key}
 * lookup.
 *
 * <p>Resolution precedence (first non-blank wins):</p>
 * <ol>
 *   <li>{@code <providerName>.api.key} system property — provider-specific
 *       override (e.g. {@code anthropic.api.key}, {@code cohere.api.key})</li>
 *   <li>{@code LLM_API_KEY} — system property, then environment variable</li>
 *   <li>{@code OPENAI_API_KEY} — system property, then environment variable</li>
 *   <li>{@code GEMINI_API_KEY} — system property, then environment variable</li>
 * </ol>
 *
 * <p>The {@code LLM_API_KEY} &gt; {@code OPENAI_API_KEY} &gt;
 * {@code GEMINI_API_KEY} ordering mirrors exactly how
 * {@link AiConfig#fromEnvironment()} reads keys today, so the OpenAI path's
 * resolution order is unchanged. The {@code <providerName>.api.key} system
 * property is layered on top as the highest-priority provider-specific
 * override.</p>
 *
 * <p>Pure and side-effect-free: it only reads system properties and
 * environment variables. Returns {@code null} when no key is configured.</p>
 */
public final class CredentialResolver {

    private CredentialResolver() {
    }

    /**
     * Resolve the API key for the named provider.
     *
     * @param providerName the provider name used for the
     *                      {@code <providerName>.api.key} system-property tier
     *                      (e.g. {@code "anthropic"}, {@code "cohere"}); may be
     *                      {@code null} or blank to skip that tier
     * @return the first non-blank key found per the documented precedence, or
     *         {@code null} if none is configured
     */
    public static String resolve(String providerName) {
        if (providerName != null && !providerName.isBlank()) {
            var providerKey = System.getProperty(providerName + ".api.key");
            if (providerKey != null && !providerKey.isBlank()) {
                return providerKey;
            }
        }

        var llm = property("LLM_API_KEY");
        if (llm != null) {
            return llm;
        }
        var openai = property("OPENAI_API_KEY");
        if (openai != null) {
            return openai;
        }
        return property("GEMINI_API_KEY");
    }

    /**
     * Reads a key from the system property of the given name, then the
     * environment variable of the same name. Returns {@code null} when both
     * are unset or blank.
     */
    private static String property(String name) {
        var sys = System.getProperty(name);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        var env = System.getenv(name);
        return (env != null && !env.isBlank()) ? env : null;
    }
}
