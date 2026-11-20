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
package org.atmosphere.ai.sk;

import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import org.atmosphere.ai.test.StallingHttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport policy of the client Atmosphere builds for Semantic Kernel:
 * SSE comment lines never reach the Azure SDK's parser, wherever the chunks
 * split them, and the HTTP client's timeouts sit at the stream idle bound plus
 * {@link SemanticKernelOpenAiClientFactory#TRANSPORT_GRACE}.
 */
class SemanticKernelTurnTransportTest {

    private static String filter(String... chunks) {
        var filter = new SemanticKernelTurnTransport.CommentFilter();
        var out = new StringBuilder();
        for (var chunk : chunks) {
            var buffer = filter.apply(ByteBuffer.wrap(chunk.getBytes(StandardCharsets.UTF_8)));
            // The Azure SDK's parser reads the whole array: it must be exactly the chunk.
            assertEquals(buffer.remaining(), buffer.array().length, "the filtered chunk is exactly sized");
            out.append(new String(buffer.array(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    @Test
    void commentLinesAreRemovedAndDataIsKept() {
        assertEquals("\ndata: {\"a\":\"b:c\"}\n\n",
                filter(": keep-alive\n\ndata: {\"a\":\"b:c\"}\n\n"));
    }

    @Test
    void aCommentSplitAcrossChunksIsRemoved() {
        assertEquals("data: {}\n\n\ndata: {}\n\n",
                filter("data: {}\n\n:", " keep", "-alive\n", "\ndata: {}\n\n"));
    }

    @Test
    void aColonInsideALineIsNotAComment() {
        assertEquals("data: x:y\r\n\r\n", filter("data: x:y\r\n", ":ping\r\n", "\r\n"));
    }

    @Test
    void theTransportTimeoutIsTheBoundPlusGraceOrNone() {
        assertEquals(Duration.ofMinutes(5).plus(SemanticKernelOpenAiClientFactory.TRANSPORT_GRACE),
                SemanticKernelOpenAiClientFactory.transportTimeout(Duration.ofMinutes(5)));
        assertEquals(Duration.ZERO, SemanticKernelOpenAiClientFactory.transportTimeout(Duration.ZERO));
    }

    @Test
    @Timeout(60)
    void aClientBuiltWithTheBoundGivesUpOnASilentProvider() throws Exception {
        try (var server = new StallingHttpServer(
                StallingHttpServer.Mode.stallAfterChunks(1, StallingHttpServer.Dialect.OPENAI))) {
            var client = SemanticKernelOpenAiClientFactory.forEndpoint(server.baseUrl() + "/v1", "k", false,
                    Duration.ofMillis(200));
            var options = new ChatCompletionsOptions(List.of(new ChatRequestUserMessage("Hello")));
            var started = System.nanoTime();
            assertThrows(RuntimeException.class,
                    () -> client.getChatCompletionsStream("gpt-4o-mini", options).blockLast());
            var elapsed = Duration.ofNanos(System.nanoTime() - started);
            // The Azure SDK's default read timeout is 60 s; the bound's is 200 ms plus the grace.
            assertTrue(elapsed.compareTo(Duration.ofSeconds(50)) < 0, "the read timeout ended the call: " + elapsed);
            assertTrue(server.clientClosed(Duration.ofSeconds(3)), "the timed-out call's connection closed");
        }
    }
}
