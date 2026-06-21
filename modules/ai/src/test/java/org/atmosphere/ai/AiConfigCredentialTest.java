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

import org.atmosphere.ai.llm.FakeLlmClient;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that {@link AiConfig.LlmSettings#apiKey()} carries the
 * framework-resolved provider key regardless of the concrete client type, the
 * 4-arg backward-compatible constructor preserves the historical
 * instanceof-based behavior, and
 * {@link ApiKeyResolver#resolveProvider} honors the documented
 * per-provider precedence without leaking another provider's key.
 *
 * <p>{@link AiConfig#configure} just overwrites a volatile singleton, so —
 * like {@link AiConfigTest} — no static reset is needed between cases; each
 * test reads the {@code LlmSettings} returned by its own {@code configure}
 * call. The system-property tiers are mutated under a try/finally that
 * restores the prior value; the OS environment is never mutated.</p>
 */
public class AiConfigCredentialTest {

    /**
     * Invariant: configuring remote with an explicit key against the OpenAI
     * endpoint yields {@code apiKey()} == that exact key, and the active client
     * is an {@link OpenAiCompatibleClient} built with the identical key. This
     * is the bit-for-bit guarantee — the stored component must equal what the
     * client holds.
     */
    @Test
    public void testRemoteExplicitKeyStoredAndOpenAiClient() {
        var settings = AiConfig.configure("remote", "gpt-4o", "sk-explicit-123", null);

        assertEquals("sk-explicit-123", settings.apiKey());
        var client = assertInstanceOf(OpenAiCompatibleClient.class, settings.client());
        assertEquals(client.apiKey(), settings.apiKey(),
                "stored apiKey() must equal the OpenAiCompatibleClient's key (bit-for-bit)");
    }

    /**
     * Fake/no-key mode must keep returning {@code null} from {@code apiKey()}.
     */
    @Test
    public void testFakeModeApiKeyNull() {
        var settings = AiConfig.configure("fake", "any-model", null, null);

        assertNull(settings.apiKey());
        assertSame(AiConfig.get(), settings);
    }

    /**
     * Bit-for-bit no-key contract: configuring remote with no explicit key must
     * leave {@code apiKey()} {@code null} — even when the OS environment carries
     * {@code LLM_API_KEY}/{@code OPENAI_API_KEY}/{@code GEMINI_API_KEY} — so the
     * built-in path's no-key demo-mode handoff is preserved. The stored value
     * must equal the OpenAiCompatibleClient's own (null) key.
     */
    @Test
    public void testRemoteNoKeyStaysNull() {
        var settings = AiConfig.configure("remote", "gpt-4o", null, null);

        assertNull(settings.apiKey(),
                "no explicit key must keep apiKey() null regardless of ambient env");
        var client = assertInstanceOf(OpenAiCompatibleClient.class, settings.client());
        assertNull(client.apiKey(), "OpenAiCompatibleClient must also hold a null key");
    }

    /**
     * A blank/whitespace key collapses to {@code null}, matching the
     * historical OpenAiCompatibleClient normalization.
     */
    @Test
    public void testRemoteBlankKeyStaysNull() {
        var settings = AiConfig.configure("remote", "gpt-4o", "   ", null);

        assertNull(settings.apiKey());
    }

    /**
     * The 4-arg backward-compatible constructor must default {@code apiKey} the
     * historical way: derived from an {@link OpenAiCompatibleClient}'s key.
     */
    @Test
    public void testFourArgShimOpenAiClientReturnsKey() {
        var client = OpenAiCompatibleClient.openai("sk-shim-key");
        var settings = new AiConfig.LlmSettings(client, "gpt-4o", "remote", null);

        assertEquals("sk-shim-key", settings.apiKey());
    }

    /**
     * The 4-arg shim with a non-OpenAI client must return {@code null} —
     * preserving the old instanceof behavior exactly.
     */
    @Test
    public void testFourArgShimNonOpenAiClientReturnsNull() {
        var settings = new AiConfig.LlmSettings(new FakeLlmClient("fake-model"), "fake-model", "fake", null);

        assertNull(settings.apiKey());
    }

    /**
     * The property/env names {@link ApiKeyResolver#resolveProvider} can
     * consult, plus the cross-provider keys the no-leak case probes. All are
     * cleared and restored around each case so no state leaks between tests.
     */
    private static final String[] ALL_NAMES = {
            "anthropic.api.key", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY"};

    /**
     * Tier (a): the {@code <provider>.api.key} system property outranks the
     * provider-env-var tier and the generic fallback. This preserves the
     * historical top-priority override existing Anthropic/Cohere tests rely on.
     */
    @Test
    public void testProviderPropertyWinsOverEverything() {
        withSysProps(() -> {
            System.setProperty("anthropic.api.key", "provider-specific");
            System.setProperty("ANTHROPIC_API_KEY", "from-env-var");

            assertEquals("provider-specific",
                    ApiKeyResolver.resolveProvider("anthropic", "ANTHROPIC_API_KEY", "generic-fallback"));
        });
    }

    /**
     * Tier (b): the provider-env-var tier ({@code ANTHROPIC_API_KEY}) is used
     * when no {@code anthropic.api.key} override is set, ranking above the
     * generic fallback. {@code property()} reads the system property of that
     * name before the OS environment variable, so setting the sysprop named
     * {@code ANTHROPIC_API_KEY} exercises the same tier without mutating env.
     */
    @Test
    public void testProviderEnvVarTierUsedWhenNoOverride() {
        withSysProps(() -> {
            System.setProperty("ANTHROPIC_API_KEY", "from-env-var");

            assertEquals("from-env-var",
                    ApiKeyResolver.resolveProvider("anthropic", "ANTHROPIC_API_KEY", "generic-fallback"));
        });
    }

    /**
     * Tier (c): the {@code genericFallback} (the framework-resolved
     * {@code settings.apiKey()}) is used when neither the provider sysprop nor
     * the provider env var is set.
     */
    @Test
    public void testGenericFallbackUsedWhenNoProviderKey() {
        withSysProps(() -> assertEquals("generic-fallback",
                ApiKeyResolver.resolveProvider("anthropic", "ANTHROPIC_API_KEY", "generic-fallback")));
    }

    /**
     * No cross-provider leak (Runtime Truth): with only {@code OPENAI_API_KEY}
     * set and no Anthropic key anywhere, resolving for Anthropic with a
     * {@code null} generic fallback returns {@code null} — the OpenAI key must
     * never make a non-OpenAI provider look available. This is the regression
     * the old {@code resolve(String)} OPENAI/GEMINI fallback would have failed.
     */
    @Test
    public void testNoCrossProviderLeakFromOpenAiKey() {
        withSysProps(() -> {
            System.setProperty("OPENAI_API_KEY", "sk-openai-only");

            assertNull(ApiKeyResolver.resolveProvider("anthropic", "ANTHROPIC_API_KEY", null),
                    "OPENAI_API_KEY must never resolve as an Anthropic credential");
        });
    }

    /**
     * Runs {@code body} with all probed credential property names cleared, then
     * restores each to its prior value (cleared if it was unset) so cross-test
     * state never leaks. The body sets only the system properties it needs. The
     * OS environment is never mutated.
     */
    private static void withSysProps(Runnable body) {
        var saved = new String[ALL_NAMES.length];
        for (int i = 0; i < ALL_NAMES.length; i++) {
            saved[i] = System.getProperty(ALL_NAMES[i]);
            System.clearProperty(ALL_NAMES[i]);
        }
        try {
            body.run();
        } finally {
            for (int i = 0; i < ALL_NAMES.length; i++) {
                if (saved[i] == null) {
                    System.clearProperty(ALL_NAMES[i]);
                } else {
                    System.setProperty(ALL_NAMES[i], saved[i]);
                }
            }
        }
    }
}
