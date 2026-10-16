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
package org.atmosphere.consoleownroot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An application that maps {@code GET /} itself keeps its own root page: the
 * starter's Console redirect only answers a root nothing else serves. The
 * redirect filter runs first in the chain, so before it let the application
 * answer, a controller mapped at {@code /} was never reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = OwnRootPageKeepsItsRootHttpE2eTest.TestApp.class,
        properties = {
                "atmosphere.packages=org.atmosphere.consoleownroot"
        })
class OwnRootPageKeepsItsRootHttpE2eTest {

    static final String OWN_ROOT = "the application's own root page";

    @LocalServerPort
    private int port;

    @Test
    void controllerMappedAtRootIsServedInsteadOfTheConsoleRedirect() throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "the app's own / must answer, got "
                    + response.statusCode() + " Location="
                    + response.headers().firstValue("Location").orElse(""));
            assertEquals(OWN_ROOT, response.body());
        }
    }

    // Imported explicitly: component scanning skips classes nested in a test class.
    @SpringBootApplication
    @Import(RootController.class)
    static class TestApp {
    }

    @RestController
    static class RootController {

        @GetMapping("/")
        String root() {
            return OWN_ROOT;
        }
    }
}
