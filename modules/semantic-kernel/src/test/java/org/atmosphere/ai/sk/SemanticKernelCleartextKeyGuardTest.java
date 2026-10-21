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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.ai.openai.OpenAIAsyncClient;
import org.atmosphere.ai.llm.CleartextApiKeyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins where Semantic Kernel may send an API key over plain {@code http}.
 *
 * <p>Running the URL rewrite after the Azure SDK's key-credential policy let
 * the local default {@code http://localhost:11434/v1} through, and with it
 * every other {@code http} endpoint: the key crossed the network in
 * cleartext to any host. A key now goes over {@code http} only to a loopback
 * host, or to another host when the application opts in, with a warning.</p>
 *
 * <p>Hosts under {@code .invalid} never resolve, so these tests also show the
 * decision makes no DNS lookup.</p>
 */
class SemanticKernelCleartextKeyGuardTest {

    private static final String REMOTE_HTTP = "http://llm.invalid:8080/v1";
    private static final String KEY = "sk-test-not-a-real-key";

    /**
     * Every input the client gate and the guard read, blanked, so a developer
     * shell that exports {@code LLM_*} cannot leak into the outcome.
     */
    private static final String[] NO_BACKEND = {
            "llm.api-key=", "llm.mode=", "LLM_MODE=", "LLM_API_KEY=", "llm.base-url=",
            CleartextApiKeyPolicy.ALLOW_PROPERTY + "="
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereSemanticKernelClientAutoConfiguration.class))
            .withPropertyValues(NO_BACKEND);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger factoryLogger =
            (Logger) LoggerFactory.getLogger(SemanticKernelOpenAiClientFactory.class);

    @BeforeEach
    void captureFactoryLog() {
        appender.start();
        factoryLogger.addAppender(appender);
    }

    @AfterEach
    void releaseFactoryLog() {
        factoryLogger.detachAppender(appender);
        appender.stop();
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void aKeyOverPlainHttpToANonLoopbackHostIsRefused() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelOpenAiClientFactory.forEndpoint(REMOTE_HTTP, KEY));

        assertTrue(refused.getMessage().contains(REMOTE_HTTP),
                "the error names the endpoint: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(
                        CleartextApiKeyPolicy.ALLOW_PROPERTY),
                "the error names the opt-in: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(KEY), "the error never carries the key");
    }

    @Test
    void anUpperCaseSchemeIsStillPlainHttp() {
        assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelOpenAiClientFactory.forEndpoint("HTTP://llm.invalid:8080/v1", KEY));
    }

    @Test
    void aNameThatMerelyStartsWithLocalhostIsNotLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelOpenAiClientFactory.forEndpoint("http://localhost.invalid/v1", KEY));
        assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelOpenAiClientFactory.forEndpoint("http://127.0.0.1.invalid/v1", KEY));
    }

    @Test
    void anEndpointThatIsNotAUriIsRefusedWithAKey() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelOpenAiClientFactory.forEndpoint("http://llm invalid/v1", KEY));
        assertTrue(refused.getMessage().contains("not a URI"), refused.getMessage());
    }

    @Test
    void aKeyOverPlainHttpToALoopbackHostIsAllowedWithoutAWarning() {
        for (var endpoint : List.of("http://localhost:11434/v1", "http://LOCALHOST:11434/v1",
                "http://127.0.0.1:11434/v1", "http://127.0.0.2:11434/v1",
                "http://[::1]:11434/v1", "http://[0:0:0:0:0:0:0:1]:11434/v1")) {
            assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint(endpoint, KEY), endpoint);
        }
        assertEquals(List.of(), warnings());
    }

    @Test
    void aKeylessCallToANonLoopbackHttpHostNeedsNoOptIn() {
        // A local backend on another machine (Ollama on a GPU box) needs no
        // key, and with none there is no secret to protect.
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint(REMOTE_HTTP, null));
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint(REMOTE_HTTP, " "));
        assertEquals(List.of(), warnings());
    }

    @Test
    void httpsIsUnchanged() {
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint("https://api.together.xyz/v1", KEY));
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint("https://api.openai.com/v1", KEY));
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint(null, KEY));
        assertEquals(List.of(), warnings());
    }

    @Test
    void withTheOptInAKeyGoesOverPlainHttpToANonLoopbackHostWithAWarning() {
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint(REMOTE_HTTP, KEY, true));

        var warnings = warnings();
        assertEquals(1, warnings.size(), "one warning per client: " + warnings);
        assertTrue(warnings.get(0).contains(REMOTE_HTTP), warnings.get(0));
        assertTrue(warnings.get(0).contains("CLEARTEXT"), warnings.get(0));
        assertFalse(warnings.get(0).contains(KEY), "the warning never carries the key");
    }

    @Test
    void theOptInChangesNothingWhereNoKeyGoesInCleartext() {
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint("https://api.together.xyz/v1", KEY, true));
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint("http://localhost:11434/v1", KEY, true));
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint(REMOTE_HTTP, null, true));
        assertEquals(List.of(), warnings());
    }

    @Test
    void theRuleIsTheOneEveryRuntimeShares() {
        // The loopback table is pinned by CleartextApiKeyPolicyTest; here, that
        // an IPv4-mapped loopback literal reaches the same verdict through SK.
        assertNotNull(SemanticKernelOpenAiClientFactory.forEndpoint("http://[::ffff:127.0.0.1]:11434/v1", KEY));
        assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelOpenAiClientFactory.forEndpoint("http://[::ffff:10.0.0.1]:11434/v1", KEY));
    }

    @Test
    void theApplicationStartsWithTheOptInAndWarns() {
        runner.withPropertyValues("llm.api-key=" + KEY, "llm.base-url=" + REMOTE_HTTP,
                        CleartextApiKeyPolicy.ALLOW_PROPERTY + "=true")
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    assertEquals(1, context.getBeanNamesForType(OpenAIAsyncClient.class).length);
                    assertEquals(1, warnings().size(), String.valueOf(warnings()));
                });
    }

    @Test
    void theOptInIsReadFromItsEnvironmentVariable() {
        // Operators set it as an environment variable; Spring maps the
        // upper-case, underscore form onto the property.
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-env",
                                Map.of(CleartextApiKeyPolicy.ALLOW_ENV, "true"))))
                .withPropertyValues("llm.api-key=" + KEY, "llm.base-url=" + REMOTE_HTTP)
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    assertEquals(1, context.getBeanNamesForType(OpenAIAsyncClient.class).length);
                    assertEquals(1, warnings().size(), String.valueOf(warnings()));
                });
    }

    @Test
    void onlyTrueOptsIn() {
        runner.withPropertyValues("llm.api-key=" + KEY, "llm.base-url=" + REMOTE_HTTP,
                        CleartextApiKeyPolicy.ALLOW_PROPERTY + "=yes")
                .run(context -> assertNotNull(context.getStartupFailure(), "'yes' is not an opt-in"));
    }

    @Test
    void theApplicationStartsWithAKeyOverPlainHttpToLoopback() {
        // The local default: a developer shell that exports a real key keeps
        // reaching the local Ollama.
        runner.withPropertyValues("llm.mode=local", "llm.api-key=" + KEY)
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    assertEquals(1, context.getBeanNamesForType(OpenAIAsyncClient.class).length);
                    assertEquals(List.of(), warnings());
                });
    }

    @Test
    void theApplicationRefusesToStartWithAKeyOverPlainHttpToANonLoopbackHost() {
        runner.withPropertyValues("llm.api-key=" + KEY, "llm.base-url=" + REMOTE_HTTP)
                .run(context -> {
                    var failure = context.getStartupFailure();
                    assertNotNull(failure, "the context must not start");
                    var root = rootCause(failure);
                    assertTrue(root instanceof IllegalArgumentException, String.valueOf(root));
                    assertTrue(root.getMessage().contains(REMOTE_HTTP), root.getMessage());
                });
    }

    @Test
    void theApplicationStartsAgainstAKeylessLocalBackendOnAnotherHost() {
        runner.withPropertyValues("llm.mode=local", "llm.base-url=http://gpu.invalid:11434/v1")
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    assertEquals(1, context.getBeanNamesForType(OpenAIAsyncClient.class).length);
                });
    }

    private static Throwable rootCause(Throwable failure) {
        var cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
