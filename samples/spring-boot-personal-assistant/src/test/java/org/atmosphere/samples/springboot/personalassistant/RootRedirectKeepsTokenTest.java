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
package org.atmosphere.samples.springboot.personalassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README's sign-in URL, {@code /?token=alice}, must reach the Console with
 * its token: the Console reads {@code ?token=} from its own page URL and replays
 * it as the identity long-term memory keys on. A root redirect that drops the
 * query lands every visitor on {@code /atmosphere/console/} with no identity, so
 * no memory is kept for anyone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "atmosphere.admin.enabled=false",
                "llm.api-key=",
                // Nothing listens here: the outbound MCP demo must not reach a
                // real server from a test.
                "atmosphere.mcp.client.endpoint=http://127.0.0.1:1/atmosphere/mcp"
        })
class RootRedirectKeepsTokenTest {

    @LocalServerPort
    int port;

    @Test
    void rootRedirectCarriesTheSignInTokenToTheConsole() throws Exception {
        try (var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/?token=alice"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());

            assertEquals(302, response.statusCode(), "GET / must redirect to the Console");
            var location = response.headers().firstValue("Location").orElse(null);
            assertNotNull(location, "the redirect must carry a Location header");
            assertTrue(location.endsWith("/atmosphere/console/?token=alice"),
                    "the redirect must keep ?token=alice, or the Console signs no one in; got: "
                            + location);
        }
    }
}
