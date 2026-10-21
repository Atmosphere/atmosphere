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
package org.atmosphere.ai.sk;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.ai.openai.OpenAIAsyncClient;
import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the gate that decides whether Semantic Kernel builds a client at all.
 *
 * <p>The condition was an API key alone and the base URL defaulted to OpenAI, so
 * a keyless local deployment ({@code llm.mode=local}) built no client and
 * Semantic Kernel could never serve a local backend. This is the same defect
 * already fixed in the LangChain4j, Koog and built-in paths — found here by a
 * lint that walks sample config, which surfaced that the module's own Java
 * default was the deeper instance.</p>
 *
 * <p>Driven through a real context because the gate is a
 * {@code @ConditionalOnExpression} SpEL string; asserting on the resolution
 * helpers would pass against the broken condition.</p>
 */
class SemanticKernelLocalModeAutoConfigurationTest {

    /**
     * Every input the gate reads, blanked.
     *
     * <p>Spring relaxed-binds {@code LLM_API_KEY} / {@code LLM_MODE} from the
     * process environment onto {@code llm.api-key} / {@code llm.mode}, and a
     * developer machine routinely exports both. Without this the negative case
     * asserts against whoever's shell is running Maven — green in CI, red
     * locally.</p>
     */
    private static final String[] NO_BACKEND = {
            "llm.api-key=", "llm.mode=", "LLM_MODE=", "LLM_API_KEY=", "llm.base-url="
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereSemanticKernelClientAutoConfiguration.class))
            .withPropertyValues(NO_BACKEND);

    private static int clients(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure(),
                "the context failed to start: " + context.getStartupFailure());
        return context.getBeanNamesForType(OpenAIAsyncClient.class).length;
    }

    @Test
    void localModeBuildsAClientWithoutAnyCredential() {
        // The regression. Pre-fix this context has no OpenAIAsyncClient, so the
        // whole Semantic Kernel path is dead against a local model.
        runner.withPropertyValues("llm.mode=local")
                .run(context -> assertEquals(1, clients(context),
                        "a local backend needs no credential, so an absent key must not "
                                + "suppress the client"));
    }

    @Test
    void theEnvironmentVariableAloneIsEnough() {
        // Samples that declare no `llm` block configure the backend purely
        // through the environment.
        runner.withPropertyValues("LLM_MODE=local")
                .run(context -> assertEquals(1, clients(context)));
    }


    // A blank base URL follows the model, as for the built-in runtime
    // (AiConfig.resolveBaseUrl): it used to mean OpenAI whatever the model, so
    // a sample run with a Gemini model and GEMINI_API_KEY sent that key to
    // api.openai.com.

    @Test
    void aBlankBaseUrlFollowsTheModel() {
        assertEquals(AiConfig.GEMINI_ENDPOINT,
                AtmosphereSemanticKernelClientAutoConfiguration.resolveBaseUrl("", false, "gemini-2.5-flash"));
        assertEquals(AiConfig.OPENAI_ENDPOINT,
                AtmosphereSemanticKernelClientAutoConfiguration.resolveBaseUrl(null, false, "gpt-4o-mini"));
        assertEquals(AiConfig.OLLAMA_ENDPOINT,
                AtmosphereSemanticKernelClientAutoConfiguration.resolveBaseUrl(" ", true, "gemini-2.5-flash"),
                "local mode wins over the model");
        assertEquals("https://proxy.example/v1",
                AtmosphereSemanticKernelClientAutoConfiguration.resolveBaseUrl("https://proxy.example/v1", true, "gemini-2.5-flash"),
                "an explicit base URL wins over mode and model");
    }

    @Test
    void theBuiltClientForAGeminiModelCallsGemini() {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(AtmosphereSemanticKernelClientAutoConfiguration.class);
        appender.start();
        logger.addAppender(appender);
        try {
            runner.withPropertyValues("llm.api-key=AIza-test-not-a-real-key", "llm.model=gemini-2.5-flash",
                            "llm.base-url=")
                    .run(context -> {
                        assertEquals(1, clients(context));
                        assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                                        .anyMatch(m -> m.contains("endpoint=" + AiConfig.GEMINI_ENDPOINT + " local=")),
                                "a Gemini model and key must not go to api.openai.com: " + appender.list);
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aCredentialedRemoteStillBuildsAClient() {
        // Original behaviour, unchanged.
        runner.withPropertyValues("llm.api-key=sk-test-not-a-real-key")
                .run(context -> assertEquals(1, clients(context)));
    }

    @Test
    void neitherCredentialNorLocalModeBuildsNothing() {
        // The gate must stay shut when there is nothing to talk to. Opening it
        // unconditionally would point an unconfigured app at api.openai.com with
        // a placeholder credential, turning a clear startup-time misconfiguration
        // into a 401 on the first user prompt.
        runner.run(context -> assertEquals(0, clients(context),
                "with no credential and no local backend there is nothing to talk to"));
    }
}
