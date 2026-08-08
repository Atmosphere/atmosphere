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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the gate that decides whether a default {@link StreamingChatModel} is
 * built at all.
 *
 * <p>The condition was an API key alone. A locally served backend needs no
 * credential, so a keyless {@code local} deployment built no model and every
 * prompt died with "StreamingChatModel not configured". It went unnoticed for
 * as long as it did because {@code DemoAgentRuntime} out-prioritised every real
 * runtime while the key was blank — the samples answered from a canned script
 * and looked healthy, so no assertion anywhere was reaching LangChain4j.</p>
 *
 * <p>Driven through a real context on purpose: the gate is a
 * {@code @ConditionalOnExpression} SpEL string, and a test that called the
 * resolution helpers directly would pass against the broken condition.</p>
 */
class LangChain4jLocalModeAutoConfigurationTest {

    /**
     * Every input the gate reads, blanked.
     *
     * <p>Spring relaxed-binds {@code LLM_API_KEY} / {@code LLM_MODE} from the
     * process environment onto {@code llm.api-key} / {@code llm.mode}, and a
     * developer machine routinely exports both. Without this the negative case
     * asserts against whoever's shell is running Maven — it passed in CI and
     * failed locally, which is the shape of a test nobody trusts.</p>
     */
    private static final String[] NO_BACKEND = {
            "llm.api-key=", "llm.mode=", "LLM_MODE=", "LLM_API_KEY="
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtmosphereLangChain4jAutoConfiguration.class))
            .withPropertyValues(NO_BACKEND);

    /** Number of {@link StreamingChatModel} beans, after asserting a clean start. */
    private static int models(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure(),
                "the context failed to start: " + context.getStartupFailure());
        return context.getBeanNamesForType(StreamingChatModel.class).length;
    }

    @Test
    void localModeBuildsAModelWithoutAnyCredential() {
        // The regression. Pre-fix this context has no StreamingChatModel and the
        // runtime throws on the first prompt.
        runner.withPropertyValues("llm.mode=local", "llm.model=qwen2.5:3b")
                .run(context -> assertEquals(1, models(context),
                        "a local backend needs no credential, so an absent key must not "
                                + "suppress the model"));
    }

    @Test
    void theEnvironmentVariableAloneIsEnough() {
        // Most samples configure the backend purely through the environment and
        // declare no `llm` block, so keying only on the mapped `llm.mode`
        // property fixed the two samples that happened to declare it and left
        // the rest broken. LLM_MODE resolves through the environment, which
        // covers both a real env var and -DLLM_MODE.
        runner.withPropertyValues("LLM_MODE=local", "llm.model=qwen2.5:3b")
                .run(context -> assertEquals(1, models(context),
                        "samples that declare no llm block configure the backend entirely "
                                + "through LLM_MODE"));
    }

    // Deliberately not tested here: that LLM_MODE also selects the *local
    // endpoint* in the bean body, not merely opens the condition. The resolved
    // base URL is not readable from a built OpenAiStreamingChatModel without
    // reflecting through its `internal` client, and a test that asserted only
    // bean presence would pass against the asymmetric version too — a gate that
    // cannot fail is worse than an acknowledged gap.

    @Test
    void anApiKeyStillBuildsAModel() {
        // The original behaviour, unchanged: a credentialed remote endpoint.
        runner.withPropertyValues("llm.api-key=sk-test-not-a-real-key")
                .run(context -> assertEquals(1, models(context)));
    }

    @Test
    void neitherCredentialNorLocalModeBuildsNothing() {
        // The gate must stay shut when there is nothing to talk to. Opening it
        // unconditionally would point an unconfigured app at api.openai.com with
        // a placeholder credential and turn a clear startup-time misconfiguration
        // into a 401 on the first user prompt.
        runner.run(context -> assertEquals(0, models(context),
                "with no credential and no local backend there is nothing to talk to"));
    }

    @Test
    void aUserSuppliedModelStillWins() {
        // @ConditionalOnMissingBean: supplying Anthropic/Bedrock/Ollama directly
        // must override the default, and the local-mode branch must not change that.
        runner.withPropertyValues("llm.mode=local")
                .withBean("customModel", StreamingChatModel.class,
                        () -> Mockito.mock(StreamingChatModel.class))
                .run(context -> {
                    assertEquals(1, models(context));
                    assertSame(context.getBean("customModel"),
                            context.getBean(StreamingChatModel.class),
                            "a user-supplied model must not be shadowed by the default");
                });
    }
}
