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
package org.atmosphere.samples.springboot.passivation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample is key-free: {@link DemoContinuationRuntime} is handed straight to
 * {@code AgentPassivation}, and nothing in the application uses the framework's LLM
 * settings. So booting it must not configure those settings, and must not warn about a
 * missing API key. The 2026-10-16 sweep found two such warnings and an
 * {@code AI config: mode=remote, model=gemini-2.5-flash} line at startup.
 *
 * <p>The last assertion holds whether or not the machine running the test has an API key
 * in its environment, so the test cannot pass just because a key happens to be set.</p>
 *
 * <p>The properties differ from {@link PassivationDeliveryTest}'s so this class gets its
 * own application context, booted while the output is being captured.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KeyFreeStartupLogTest {

    @LocalServerPort
    int port;

    @Test
    void startupNeitherWarnsAboutAnApiKeyNorConfiguresAnLlm(CapturedOutput output) {
        var log = output.getAll();
        assertTrue(log.contains("Started KeyFreeStartupLogTest"),
                "the application context was not booted while output was captured");
        assertFalse(log.contains("No AI API key configured"), "startup warned about a missing AI API key");
        assertFalse(log.contains("No API key configured for remote mode"),
                "startup warned about a missing remote-mode API key");
        assertFalse(log.contains("Atmosphere AI configured: mode="),
                "startup configured framework LLM settings the sample never uses");
    }

    /**
     * Turning the AI auto-configuration off keeps the starter's {@code GET /}
     * redirect to the Console, which is registered beside the Console itself.
     */
    @Test
    void rootStillOpensTheConsole() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElse("").endsWith("/atmosphere/console/"),
                "GET / should redirect to the Console, got " + response.headers().map());
    }
}
