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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiConfigTestSupport;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@link AiEnvironment} shuts down, when it closes, the HTTP clients of the
 * settings it built itself, and only those (Correctness Invariant 1): a
 * stopped application (a Spring test-context cache eviction, a Quarkus dev
 * reload) does not leave its HTTP client's selector thread running, and never
 * shuts down a client it was given or one the process default still hands out.
 */
class AiEnvironmentClientShutdownTest {

    private static final String URL = "http://127.0.0.1:1/v1";
    /** How long a client left open is watched for terminating: a shut-down idle client terminates at once. */
    private static final Duration OPEN = Duration.ofMillis(500);

    @BeforeEach
    void setUp() {
        AiConfig.resetHostConfig();
        AiConfigTestSupport.resetSettings();
    }

    @AfterEach
    void tearDown() {
        AiConfig.resetHostConfig();
        AiConfigTestSupport.resetSettings();
    }

    private static AiEnvironment application() {
        return AiEnvironment.create(Map.<String, String>of()::get);
    }

    private static HttpClient httpClientOf(AiConfig.LlmSettings settings) {
        return ((OpenAiCompatibleClient) settings.client()).httpClient();
    }

    @Test
    void closingAnApplicationShutsDownTheClientOfTheSettingsItBuilt() throws InterruptedException {
        var application = application();
        var http = httpClientOf(application.configure("remote", "model-a", "k", URL));
        var agentHttp = httpClientOf(application.configureForAgent("agent-a", "remote", "model-agent", "k",
                URL, null));
        assertFalse(http.isTerminated());

        application.close();

        assertTrue(http.awaitTermination(Duration.ofSeconds(5)), "the application's own client is shut down");
        assertTrue(agentHttp.awaitTermination(Duration.ofSeconds(5)), "its agent's client is shut down");
    }

    @Test
    void aClientTheApplicationWasGivenIsLeftOpen() throws InterruptedException {
        var application = application();
        application.configure("remote", "model-a", "k", URL);
        var given = OpenAiCompatibleClient.builder().baseUrl(URL).apiKey("k").build();
        application.installClient(given);

        application.close();

        assertFalse(given.httpClient().awaitTermination(OPEN), "a client installed by the application belongs to it");
    }

    /**
     * Settings the process default took from an application that closed
     * earlier are still handed out by the static API once the last application
     * closes: their client stays open.
     */
    @Test
    void settingsTheProcessDefaultStillHoldsKeepTheirClient() throws InterruptedException {
        var a = application();
        AiConfig.configure("remote", "static-model", "k", URL);
        var b = application();
        var settingsOfB = b.configure("remote", "model-b", "k", URL);
        a.close();
        assertSame(settingsOfB, AiEnvironment.processDefault().settings());

        b.close();

        assertSame(settingsOfB, AiConfig.get(), "the static API still returns them");
        assertFalse(httpClientOf(settingsOfB).awaitTermination(OPEN), "the client the static API hands out stays open");
    }

    @Test
    void anHttpClientGivenToTheBuilderIsNotShutDownByTheClient() throws InterruptedException {
        var http = HttpClient.newHttpClient();
        try {
            var client = OpenAiCompatibleClient.builder().baseUrl(URL).httpClient(http).build();

            client.shutdown();

            assertFalse(http.awaitTermination(OPEN), "the caller's HTTP client belongs to the caller");
        } finally {
            http.shutdownNow();
        }
    }
}
