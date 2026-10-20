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
package org.atmosphere.consoleroot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On Spring Boot 3, an application that serves nothing at {@code /} sends
 * {@code GET /} and {@code HEAD /} to the Console with the query kept, as on
 * Spring Boot 4. The samples dropped their own root page for this redirect,
 * and the Console probes the origin with {@code HEAD /} on every AG-UI
 * connect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ConsoleRootRedirectHttpE2eTest.TestApp.class,
        properties = "atmosphere.packages=org.atmosphere.consoleroot")
class ConsoleRootRedirectHttpE2eTest {

    @LocalServerPort
    private int port;

    @Test
    void getOfTheRootRedirectsToTheConsoleKeepingTheQuery() throws Exception {
        assertRedirects("GET");
    }

    @Test
    void headOfTheRootRedirectsToTheConsoleKeepingTheQuery() throws Exception {
        assertRedirects("HEAD");
    }

    private void assertRedirects(String method) throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/?token=x"))
                            .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(302, response.statusCode(), method + " / must redirect to the Console");
            var location = response.headers().firstValue("Location").orElse("");
            assertTrue(location.endsWith("/atmosphere/console/?token=x"),
                    method + " / must point at the Console with its query, Location=" + location);
        }
    }

    @SpringBootApplication
    static class TestApp {
    }
}
