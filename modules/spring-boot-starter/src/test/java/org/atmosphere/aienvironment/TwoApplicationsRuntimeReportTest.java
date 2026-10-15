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
package org.atmosphere.aienvironment;

import org.atmosphere.admin.ai.AiRuntimeController;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.interactions.InteractionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two Spring Boot applications in one JVM select different runtimes; the
 * console's {@code /api/console/info} and the admin {@link AiRuntimeController}
 * of each report, and its interactions service runs, that application's own
 * runtime (Runtime Truth), not the one
 * the JVM-wide static API resolves while two applications run. Both contexts
 * live in this one class: surefire gives every test class its own JVM.
 */
class TwoApplicationsRuntimeReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void closeContexts() {
        contexts.forEach(ConfigurableApplicationContext::close);
    }

    private ConfigurableApplicationContext start(String... properties) {
        var all = new ArrayList<>(List.of("server.port=0", "atmosphere.admin.require-principal=false"));
        all.addAll(List.of(properties));
        var context = new SpringApplicationBuilder(Application.class)
                .properties(all.toArray(String[]::new))
                .run();
        contexts.add(context);
        return context;
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @Test
    void consoleAndAdminReportEachApplicationsOwnRuntime() throws Exception {
        var builtIn = start(
                "atmosphere.ai.mode=remote",
                "atmosphere.ai.api-key=k",
                "atmosphere.ai.base-url=http://127.0.0.1:9/v1",
                "atmosphere.ai.model=model-built-in",
                "atmosphere.ai.runtime=built-in");
        // Fake mode reaches no model, so the demo runtime is available, and
        // the knob selects it.
        var demo = start(
                "atmosphere.ai.mode=fake",
                "atmosphere.ai.model=model-demo",
                "atmosphere.ai.runtime=demo");

        assertEquals("built-in", consoleRuntime(builtIn), "the console of the built-in application");
        assertEquals("demo", consoleRuntime(demo), "the console of the demo application");
        assertEquals("built-in", builtIn.getBean(AiRuntimeController.class).getActiveRuntime().get("name"),
                "the admin runtime controller of the built-in application");
        assertEquals("demo", demo.getBean(AiRuntimeController.class).getActiveRuntime().get("name"),
                "the admin runtime controller of the demo application");
        assertEquals("built-in", interactionsRuntime(builtIn), "the interactions service of the built-in application");
        assertEquals("demo", interactionsRuntime(demo), "the interactions service of the demo application");
        // Read at request time, while both applications run.
        assertEquals("model-built-in", interactionsDefaultModel(builtIn),
                "the interactions service's default model is its own application's");
        assertEquals("model-demo", interactionsDefaultModel(demo),
                "the interactions service's default model is its own application's");
    }

    private static String interactionsDefaultModel(ConfigurableApplicationContext context) throws Exception {
        var field = InteractionService.class.getDeclaredField("defaultModelSupplier");
        field.setAccessible(true);
        return String.valueOf(
                ((java.util.function.Supplier<?>) field.get(context.getBean(InteractionService.class))).get());
    }

    private static String interactionsRuntime(ConfigurableApplicationContext context) throws Exception {
        var field = InteractionService.class.getDeclaredField("runtime");
        field.setAccessible(true);
        return ((AgentRuntime) field.get(context.getBean(InteractionService.class))).name();
    }

    private static String consoleRuntime(ConfigurableApplicationContext context) throws Exception {
        var port = context.getEnvironment().getProperty("local.server.port");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/console/info")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return MAPPER.readTree(response.body()).get("runtime").asString();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {
    }
}
