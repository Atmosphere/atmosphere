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

import org.atmosphere.ai.test.StallingHttpServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport timeout of the {@code ChatClient} Atmosphere builds. Spring
 * AI's {@code AbstractOpenAiOptions.DEFAULT_TIMEOUT} (60 s) reaches the OpenAI
 * SDK as {@code ClientOptions.timeout(Duration)}, which is a <em>request</em>
 * timeout (OkHttp's {@code callTimeout}: the whole call). This test settles
 * whether that cuts a healthy stream: the provider sends a chunk every two
 * seconds for 66 seconds, never silent for long, and the stream must complete.
 * The timeout that replaces it is a read timeout, the application's stream idle
 * bound plus {@link AtmosphereSpringAiAutoConfiguration#TRANSPORT_GRACE}: a
 * provider silent for that long is cut by the client itself.
 */
class SpringAiTransportTimeoutTest {

    @Test
    void aHealthyStreamLongerThanSixtySecondsIsNotCut() throws Exception {
        try (var server = new StallingHttpServer(StallingHttpServer.Mode.slowButLive(
                Duration.ofSeconds(2), Duration.ofSeconds(66), StallingHttpServer.Dialect.OPENAI))) {
            var client = new AtmosphereSpringAiAutoConfiguration().atmosphereChatClient(
                    "test-key", server.baseUrl() + "/v1", "gpt-4o-mini");
            var text = new StringBuilder();
            var started = System.nanoTime();
            client.prompt().user("Hello").stream().content()
                    .doOnNext(text::append)
                    .blockLast(Duration.ofSeconds(120));
            var took = Duration.ofNanos(System.nanoTime() - started);
            assertTrue(took.compareTo(Duration.ofSeconds(60)) > 0,
                    "the stream ended before the 60 s mark: " + took);
            // Thirty-three chunks over 66 s, and the closing one.
            assertTrue(text.length() >= StallingHttpServer.CHUNK_TEXT.length() * 30,
                    "the healthy stream was cut after " + took + ": " + text.length() + " chars");
        }
    }

    @Test
    void aSilentStreamIsCutByTheReadTimeoutTheBoundPlusGrace() throws Exception {
        var environment = org.atmosphere.ai.AiEnvironment.create(
                java.util.Map.of(org.atmosphere.ai.AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "1s")::get);
        try (var server = new StallingHttpServer(
                StallingHttpServer.Mode.stallAfterHeaders(StallingHttpServer.Dialect.OPENAI))) {
            var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                    java.util.Map.of("aiEnvironment", environment));
            var client = new AtmosphereSpringAiAutoConfiguration(
                    beans.getBeanProvider(org.atmosphere.ai.AiEnvironment.class))
                    .atmosphereChatClient("test-key", server.baseUrl() + "/v1", "gpt-4o-mini");
            var started = System.nanoTime();
            assertThrows(RuntimeException.class, () -> client.prompt().user("Hello").stream().content()
                    .blockLast(Duration.ofSeconds(120)));
            var took = Duration.ofNanos(System.nanoTime() - started);
            var expected = Duration.ofSeconds(1).plus(AtmosphereSpringAiAutoConfiguration.TRANSPORT_GRACE);
            assertTrue(took.compareTo(expected) >= 0 && took.compareTo(expected.plusSeconds(30)) < 0,
                    "the stalled stream should end on the read timeout (" + expected + "), took " + took);
            assertTrue(server.clientClosed(Duration.ofSeconds(5)), "the read timeout did not close the connection");
        } finally {
            environment.close();
        }
    }
}
