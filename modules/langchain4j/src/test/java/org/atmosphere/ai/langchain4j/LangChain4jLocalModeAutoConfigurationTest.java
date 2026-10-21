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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    // That LLM_MODE also selects the *local endpoint* in the bean body, not
    // merely opens the condition: the resolved base URL is not readable from a
    // built OpenAiStreamingChatModel without reflecting through its `internal`
    // client, so it is read from the startup line the bean logs.

    @Test
    void theEnvironmentVariableSelectsTheLocalEndpoint() {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(AtmosphereLangChain4jAutoConfiguration.class);
        appender.start();
        logger.addAppender(appender);
        try {
            runner.withPropertyValues("LLM_MODE=local", "llm.model=gemini-2.5-flash")
                    .run(context -> {
                        assertEquals(1, models(context));
                        assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                                        .anyMatch(m -> m.contains("endpoint=" + AiConfig.OLLAMA_ENDPOINT + " ")),
                                "LLM_MODE=local must select the local endpoint: " + appender.list);
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }


    // A blank base URL follows the model, as for the built-in runtime
    // (AiConfig.resolveBaseUrl): it used to mean OpenAI whatever the model, so
    // a sample run with a Gemini model and GEMINI_API_KEY sent that key to
    // api.openai.com.

    @Test
    void aBlankBaseUrlFollowsTheModel() {
        assertEquals(AiConfig.GEMINI_ENDPOINT,
                AtmosphereLangChain4jAutoConfiguration.resolveBaseUrl("", false, "gemini-2.5-flash"));
        assertEquals(AiConfig.OPENAI_ENDPOINT,
                AtmosphereLangChain4jAutoConfiguration.resolveBaseUrl(null, false, "gpt-4o-mini"));
        assertEquals(AiConfig.OLLAMA_ENDPOINT,
                AtmosphereLangChain4jAutoConfiguration.resolveBaseUrl(" ", true, "gemini-2.5-flash"),
                "local mode wins over the model");
        assertEquals("https://proxy.example/v1",
                AtmosphereLangChain4jAutoConfiguration.resolveBaseUrl("https://proxy.example/v1", true, "gemini-2.5-flash"),
                "an explicit base URL wins over mode and model");
    }

    @Test
    void theBuiltModelForAGeminiModelCallsGemini() {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(AtmosphereLangChain4jAutoConfiguration.class);
        appender.start();
        logger.addAppender(appender);
        try {
            runner.withPropertyValues("llm.api-key=AIza-test-not-a-real-key", "llm.model=gemini-2.5-flash",
                            "llm.base-url=")
                    .run(context -> {
                        assertEquals(1, models(context));
                        assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                                        .anyMatch(m -> m.contains("endpoint=" + AiConfig.GEMINI_ENDPOINT + " ")),
                                "a Gemini model and key must not go to api.openai.com: " + appender.list);
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }


    /**
     * The gate reads the mode as the bean body and {@code AiConfig} do: trimmed
     * and case-insensitive. A case-sensitive {@code == 'local'} built no model
     * for {@code LLM_MODE=Local}, though the body would have called the local
     * endpoint.
     */
    @Test
    void theGateReadsTheModeTrimmedAndCaseInsensitive() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("mode", Map.of("LLM_MODE", " Local "))))
                .run(context -> assertEquals(1, models(context),
                        "LLM_MODE=' Local ' is local mode"));
        runner.withPropertyValues("llm.mode=LOCAL")
                .run(context -> assertEquals(1, models(context), "llm.mode=LOCAL is local mode"));
    }

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
