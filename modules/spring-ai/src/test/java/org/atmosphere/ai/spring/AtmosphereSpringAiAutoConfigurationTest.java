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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the Spring AI 2.0.0-M5+ bean-wiring drift: building an
 * {@code OpenAiChatModel} requires both sync and async {@code OpenAIClient}s
 * (M2 only needed the bespoke {@code OpenAiApi}). Constructing the bean with
 * only the sync client throws
 * {@code IllegalStateException("At least one credential source must be
 * specified")} at bean-construction time; constructing with null timeout/maxRetries
 * for the Kotlin SDK builders throws
 * {@code NullPointerException("Parameter specified as non-null is null")}.
 *
 * <p>Both failures slipped through unit tests because the contract tests mock
 * {@link ChatClient} and never exercise the real OpenAI SDK wiring. This test
 * directly invokes the {@code @Bean} factory method so a future regression of
 * the same shape breaks the build instead of breaking CI: CLI 7 minutes after
 * push.</p>
 */
class AtmosphereSpringAiAutoConfigurationTest {

    private final AtmosphereSpringAiAutoConfiguration autoConfig =
            new AtmosphereSpringAiAutoConfiguration();

    @BeforeEach
    @AfterEach
    void clearStaticBindings() {
        SpringAiAgentRuntime.clearChatClientBinding();
        AgentRuntimeResolver.clearExplicitClientBinding();
    }

    @Test
    void runtimeBeanDoesNotClobberAnExplicitlyBoundClient() {
        // 4.0.60 release-gate regression: an app that binds a caller-built
        // ChatClient (carrying defaultAdvisors) had it silently replaced by
        // the auto-detected context bean, so the default advisors never
        // fired. The bean factory must offer, never bind.
        var boundByApp = Mockito.mock(ChatClient.class);
        SpringAiAgentRuntime.setChatClient(boundByApp);

        var contextBean = Mockito.mock(ChatClient.class);
        @SuppressWarnings("unchecked") // Mockito cannot mock the generic type reified
        ObjectProvider<ChatClient> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(contextBean);

        autoConfig.springAiAgentRuntime(provider);

        assertSame(boundByApp, SpringAiAgentRuntime.boundClient(),
                "auto-configuration must not replace an explicitly bound ChatClient");
    }

    @Test
    void runtimeBeanBindsTheContextClientWhenNothingIsBound() {
        var contextBean = Mockito.mock(ChatClient.class);
        @SuppressWarnings("unchecked") // Mockito cannot mock the generic type reified
        ObjectProvider<ChatClient> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(contextBean);

        autoConfig.springAiAgentRuntime(provider);

        assertSame(contextBean, SpringAiAgentRuntime.boundClient(),
                "with no explicit binding the context ChatClient is the default");
    }

    @Test
    void explicitBindingMarksTheResolverSoDemoStepsAside() {
        SpringAiAgentRuntime.setChatClient(Mockito.mock(ChatClient.class));

        assertTrue(AgentRuntimeResolver.hasExplicitClientBinding(),
                "setChatClient must record the explicit binding — the demo "
                        + "fallback keys availability off it so a bound offline "
                        + "client is not shadowed by canned responses");
    }

    @Test
    void atmosphereChatClient_returnsNullWhenApiKeyMissing() {
        ChatClient client = autoConfig.atmosphereChatClient(
                "", "https://api.openai.com", "gpt-4o-mini");
        assertNull(client, "ChatClient must be null when no API key is configured");
    }

    @Test
    void atmosphereChatClient_buildsWithDummyApiKey() {
        // A non-blank API key is enough — the OpenAI SDK does not validate the key
        // at construction time (only on first request). The key surface this test
        // protects is bean construction: builders, timeouts, sync+async client
        // pairing, OpenAiSetup defaults.
        ChatClient client = autoConfig.atmosphereChatClient(
                "sk-dummy-test-key-not-real", "https://api.openai.com", "gpt-4o-mini");
        assertNotNull(client, "ChatClient must construct cleanly with a non-blank API key");
    }
}
