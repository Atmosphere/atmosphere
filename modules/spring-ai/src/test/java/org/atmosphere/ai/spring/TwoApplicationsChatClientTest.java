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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.CollectingSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two Spring contexts in one JVM, each with its own {@link ChatClient} bean and
 * its own {@link AiEnvironment} (the bean the Atmosphere starter registers),
 * dispatch each through its own client. The auto-configuration used to offer
 * the context's bean to the JVM-wide static binder, where the first context's
 * client won and the second context's requests went through it.
 */
class TwoApplicationsChatClientTest {

    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private final List<AiEnvironment> environments = new ArrayList<>();

    @AfterEach
    void close() {
        contexts.forEach(AnnotationConfigApplicationContext::close);
        environments.forEach(AiEnvironment::close);
        SpringAiAgentRuntime.clearChatClientBinding();
        AgentRuntimeResolver.clearExplicitClientBinding();
    }

    @Test
    void eachContextDispatchesThroughItsOwnChatClient() throws Exception {
        var modelA = new RecordingChatModel("from-a");
        var modelB = new RecordingChatModel("from-b");
        var environmentA = start(modelA);
        var environmentB = start(modelB);

        var replyA = dispatch(environmentA, "to-a");
        var replyB = dispatch(environmentB, "to-b");

        assertEquals("from-a", replyA, "application A answers through its own ChatClient");
        assertEquals("from-b", replyB, "application B answers through its own ChatClient");
        assertEquals(List.of("to-a"), modelA.prompts, "only application A's prompt reached A's model");
        assertEquals(List.of("to-b"), modelB.prompts, "only application B's prompt reached B's model");
        assertTrue(environmentA.hasExplicitBinding() && environmentB.hasExplicitBinding(),
                "each offered ChatClient marks its own application so the demo runtime yields");
    }

    private AiEnvironment start(ChatModel model) {
        var environment = AiEnvironment.create(key -> null);
        environments.add(environment);
        var context = new AnnotationConfigApplicationContext();
        contexts.add(context);
        context.registerBean("atmosphereAiEnvironment", AiEnvironment.class, () -> environment);
        context.registerBean("chatClient", ChatClient.class, () -> ChatClient.builder(model).build());
        context.register(AtmosphereSpringAiAutoConfiguration.class);
        context.refresh();
        return environment;
    }

    private static String dispatch(AiEnvironment environment, String message) throws Exception {
        AgentRuntime runtime = environment.runtimes().resolveAll().stream()
                .filter(candidate -> "spring-ai".equals(candidate.name()))
                .findFirst()
                .orElseThrow();
        runtime.configure(environment.settings());
        var session = new CollectingSession();
        runtime.execute(new AgentExecutionContext(
                message, null, "test-model", null, "session-" + message, "user", "conv-" + message,
                List.of(), null, null, List.of(), Map.of(), List.of(), null, null), session);
        assertTrue(session.await(Duration.ofSeconds(10)), "the dispatch completes");
        assertNull(session.failure(), "the dispatch succeeds");
        return session.text();
    }

    /** A Spring AI model that answers with a fixed text and records the user messages it receives. */
    private static final class RecordingChatModel implements ChatModel {

        private final String answer;
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        private RecordingChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getUserMessage().getText());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
