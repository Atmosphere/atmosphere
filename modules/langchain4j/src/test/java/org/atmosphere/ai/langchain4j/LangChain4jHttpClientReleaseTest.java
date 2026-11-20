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
package org.atmosphere.ai.langchain4j;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.test.StallingHttpServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider that sends its response headers and then nothing: no stream
 * event, so LangChain4j hands over no {@code StreamingHandle}. On the model
 * Atmosphere builds, the {@code AtmosphereLc4jHttpClient} under it registered
 * the response body with the turn when the headers arrived, so the turn's
 * cancel closes the connection anyway (before, it stayed open until the
 * provider ended it).
 */
class LangChain4jHttpClientReleaseTest {

    @Test
    void aCancelAfterTheHeadersBeforeAnyEventClosesTheConnection() throws Exception {
        try (var server = new StallingHttpServer(
                StallingHttpServer.Mode.stallAfterHeaders(StallingHttpServer.Dialect.OPENAI))) {
            var runtime = new LangChain4jAgentRuntime();
            runtime.configure(new AiConfig.LlmSettings(null, "gpt-4o-mini", "remote",
                    server.baseUrl() + "/v1", "stall-test-key"));
            // A long bound: the cancel, not the watchdog, ends the turn.
            var environment = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "60s")::get);
            try {
                runtime.attach(environment);
                var session = new org.atmosphere.ai.CollectingSession();
                var handle = runtime.executeWithHandle(new AgentExecutionContext(
                        "Hello", "You are helpful", "gpt-4o-mini", null, "session-1", "user-1", null,
                        List.of(), null, null, List.of(), Map.of(), List.of(), null, null), session);
                assertTrue(server.awaitRequest(Duration.ofSeconds(10)), "the request never reached the server");
                // Let the headers reach the client before the cancel.
                Thread.sleep(300);
                handle.cancel();
                assertTrue(server.clientClosed(Duration.ofSeconds(3)),
                        "the turn was cancelled after the headers, before any event, but its connection stayed open");
            } finally {
                environment.close();
            }
        }
    }
}
