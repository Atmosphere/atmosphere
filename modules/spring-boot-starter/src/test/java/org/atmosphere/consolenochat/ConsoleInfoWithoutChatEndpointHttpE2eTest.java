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
package org.atmosphere.consolenochat;

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /api/console/info} on an application that serves no chat endpoint.
 * Boots the starter with the AI auto-configuration off
 * ({@code atmosphere.ai.enabled=false}) and a scan package holding no
 * Atmosphere handler — the shape of {@code spring-boot-passivation-agent},
 * which drives its runtime from a REST controller. Nothing is mapped at
 * {@code /atmosphere/ai-chat}, so console info must not point the Console
 * there: a Console that connects to it shows "Connecting..." forever while the
 * server answers 404 (Runtime Truth — Invariant #5).
 *
 * <p>The starter's test classpath carries a build-time annotation index that
 * registers sibling tests' {@code @ManagedService} classes whatever
 * {@code atmosphere.packages} says, so the test drops those mappings first to
 * stand in the sample's state: an initialized framework serving no handler.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ConsoleInfoWithoutChatEndpointHttpE2eTest.TestApp.class,
        properties = {
                "atmosphere.packages=org.atmosphere.consolenochat",
                "atmosphere.ai.enabled=false"
        })
class ConsoleInfoWithoutChatEndpointHttpE2eTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AtmosphereFramework framework;

    @Test
    void consoleInfoAdvertisesNoChatEndpointWhenNoneIsServed() throws Exception {
        assertTrue(framework.initialized(),
                "the framework must have finished init() before the server answers");
        framework.getAtmosphereHandlers().clear();

        var body = consoleInfo();

        assertFalse(body.contains("/atmosphere/ai-chat"),
                "no handler serves /atmosphere/ai-chat, yet console info advertises it: " + body);
        assertTrue(body.contains("\"hasChat\":false"),
                "console info must say there is no chat endpoint, got: " + body);
        assertFalse(body.contains("\"endpoint\""),
                "console info must not name a chat endpoint, got: " + body);
    }

    private String consoleInfo() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/console/info")).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "console info endpoint must respond");
            return response.body();
        }
    }

    @SpringBootApplication
    static class TestApp {
    }
}
