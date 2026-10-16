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
package org.atmosphere.ai.quarkus.langchain4j.deployment;

import dev.langchain4j.model.chat.StreamingChatModel;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.langchain4j.LangChain4jAgentRuntime;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * With the Atmosphere Quarkus extension, the bridge hands the Quarkus
 * LangChain4j {@link StreamingChatModel} to the application's
 * {@link AiEnvironment}, not to the JVM: the model is there before the
 * framework initializes and its {@code @AiEndpoint} registers, and no
 * process-wide binding is made, so a second application in the JVM does not
 * use it. The provider is the real
 * {@code quarkus-langchain4j-openai} one; no request is sent.
 */
public class AtmosphereQuarkusLangChain4jBridgeTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AtmosphereQuarkusLangChain4jBridgeTest.class,
                    LateObserver.class, Chat.class))
            .overrideConfigKey("quarkus.atmosphere.packages", Chat.class.getPackageName())
            .overrideConfigKey("quarkus.langchain4j.openai.api-key", "test-key")
            .overrideConfigKey("quarkus.langchain4j.openai.base-url", "http://localhost:9/v1/")
            .overrideConfigKey("quarkus.http.test-port", "0");

    /** An {@code @AiEndpoint} that registers, and resolves its runtime, while the framework initializes. */
    @AiEndpoint(path = "/atmosphere/bridge-test-chat")
    public static class Chat {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.stream(message);
        }
    }

    /** Observes the environment after the bridge, so it sees what the bridge did before the framework initialized. */
    @ApplicationScoped
    public static class LateObserver {

        private volatile Object modelAtAttach;
        private volatile boolean frameworkInitialized;

        void observe(@Observes @Priority(Interceptor.Priority.APPLICATION + 1000) AiEnvironment environment) {
            modelAtAttach = environment.client(LangChain4jAgentRuntime.class, StreamingChatModel.class, null);
            frameworkInitialized = LazyAtmosphereConfigurator.getFramework() != null;
        }

        Object modelAtAttach() {
            return modelAtAttach;
        }

        boolean frameworkInitialized() {
            return frameworkInitialized;
        }
    }

    @Inject
    AiEnvironment environment;

    @Inject
    StreamingChatModel model;

    @Inject
    LateObserver lateObserver;

    @Test
    public void theModelIsTheApplicationsBeforeItsEndpointsRegister() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");
        assertSame(environment, AiEnvironment.find(framework.getAtmosphereConfig()).orElse(null),
                "the framework's environment is the CDI bean");

        assertSame(model, environment.client(LangChain4jAgentRuntime.class, StreamingChatModel.class, null),
                "the application's LangChain4j runtime uses the Quarkus model");
        assertSame(model, lateObserver.modelAtAttach(),
                "the model was offered when the framework attached the environment");
        assertFalse(lateObserver.frameworkInitialized(),
                "... which is before the framework initialized and the @AiEndpoint registered");
    }

    /**
     * A second application in the JVM, a framework with an environment of its
     * own, neither inherits the explicit binding nor streams through the
     * model: the LangChain4j runtime it resolves has none.
     */
    @Test
    public void aSecondApplicationDoesNotUseTheModel() throws Exception {
        assertFalse(AiEnvironment.processDefault().hasExplicitBinding(),
                "the model is bound for this application, not for every application in the JVM");
        var secondFramework = new AtmosphereFramework(false, false);
        try (var second = AiEnvironment.create(secondFramework.getAtmosphereConfig(), Map.<String, String>of()::get)) {
            assertSame(second, AiEnvironment.find(secondFramework.getAtmosphereConfig()).orElse(null));
            assertFalse(second.hasExplicitBinding(), "a second application in the JVM does not inherit the binding");
            var runtime = second.runtimes().resolveAll().stream()
                    .filter(LangChain4jAgentRuntime.class::isInstance)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the second application resolves the LangChain4j runtime"));
            assertNull(applicationModel(runtime), "the second application's LangChain4j runtime has no model");
        } finally {
            secondFramework.destroy();
        }
    }

    // The model a runtime instance streams through: its application's
    // binding, else the JVM's. Private, so read reflectively.
    private static Object applicationModel(Object runtime) throws ReflectiveOperationException {
        var method = LangChain4jAgentRuntime.class.getDeclaredMethod("applicationModel");
        method.setAccessible(true);
        return method.invoke(runtime);
    }
}
