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
 * each provider-specific {@link org.atmosphere.ai.AgentRuntime} adapter can pick
 * up the conventional {@code <PROVIDER>_API_KEY} environment variable (the same
 * name the provider's own SDK reads) without inventing its own lookup.
 *
 * <p>Resolution precedence (first non-blank wins):</p>
 * <ol>
 *   <li>{@code <providerName>.api.key} system property — the provider-specific
 *       override (e.g. {@code anthropic.api.key}, {@code cohere.api.key}). This
 *       remains the top-priority knob so a deployment that already pins the
 *       sysprop is unaffected.</li>
 *   <li>{@code <providerEnvVar>} — system property, then environment variable,
 *       of the provider's own conventional key name (e.g.
 *       {@code ANTHROPIC_API_KEY}, {@code COHERE_API_KEY}).</li>
 *   <li>{@code genericFallback} — the framework-resolved generic key the caller
 *       passes in (i.e. {@code settings.apiKey()}, which already covers explicit
 *       configuration plus the {@code LLM_API_KEY} fallback chain that
 *       {@link AiConfig#fromEnvironment()} reads).</li>
 * </ol>
 *
 * <p><strong>No cross-provider fallback.</strong> This resolver deliberately
 * never falls back to {@code OPENAI_API_KEY} or {@code GEMINI_API_KEY}. Doing so
 * would let an availability check for one provider succeed on a completely
 * different provider's key — e.g. report Anthropic as "available" when only
 * {@code OPENAI_API_KEY} is set, whose first POST to
 * {@code api.anthropic.com} would 401. Availability must reflect the running
 * provider's own confirmed credential, not another provider's key (Correctness
 * Invariant #5, Runtime Truth). The OpenAI/Gemini chain stays inside
 * {@link AiConfig#fromEnvironment()}, which only the built-in OpenAI-compatible
 * runtime consumes.</p>
 *
 * <p>Pure and side-effect-free: it only reads system properties and
 * environment variables. Returns {@code null} when no key is configured.</p>
 */
public final class ApiKeyResolver {

    private ApiKeyResolver() {
    }

    /**
     * Resolve the API key for the named provider per the documented per-provider
     * precedence.
     *
     * @param providerName    the provider name used for the
     *                        {@code <providerName>.api.key} system-property tier
     *                        (e.g. {@code "anthropic"}, {@code "cohere"}); may be
     *                        {@code null} or blank to skip that tier
     * @param providerEnvVar  the provider's conventional key name, read first as
     *                        a system property and then as an environment
     *                        variable (e.g. {@code "ANTHROPIC_API_KEY"},
     *                        {@code "COHERE_API_KEY"}); may be {@code null} or
     *                        blank to skip that tier
     * @param genericFallback the framework-resolved generic key (typically
     *                        {@code settings.apiKey()}); may be {@code null}
     * @return the first non-blank key found per the documented precedence, or
     *         {@code null} if none is configured. Never falls back to another
     *         provider's key.
     */
    public static String resolveProvider(String providerName, String providerEnvVar, String genericFallback) {
        if (providerName != null && !providerName.isBlank()) {
            var providerKey = System.getProperty(providerName + ".api.key");
            if (providerKey != null && !providerKey.isBlank()) {
                return providerKey;
            }
        }

        if (providerEnvVar != null && !providerEnvVar.isBlank()) {
            var envKey = property(providerEnvVar);
            if (envKey != null) {
                return envKey;
            }
        }

        return (genericFallback != null && !genericFallback.isBlank()) ? genericFallback : null;
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
