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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof that an inbound image submitted to a multi-modal
 * {@code @AiEndpoint} turn reaches the outbound model request as an OpenAI
 * {@code image_url} content block — the production wiring behind the blog claim
 * "Take vision input."
 *
 * <p>The chain under test is exactly the one the {@code AiEndpointHandler}
 * drives on a real websocket frame:</p>
 * <ol>
 *   <li>{@link MultiModalInput#decode(String)} turns the inbound
 *       {@code {"type":"content","contentType":"image",...}} frame into a text
 *       prompt plus a {@link Content.Image} part;</li>
 *   <li>{@link AiStreamingSession#setPendingInputParts(List)} stashes the part;</li>
 *   <li>the {@code @Prompt}-style call {@code session.stream(text)} threads the
 *       stashed part into the {@link AgentExecutionContext};</li>
 *   <li>the built-in runtime's {@code buildRequest} carries it onto the
 *       {@link ChatCompletionRequest}, and {@code OpenAiCompatibleClient} encodes
 *       it as {@code image_url} on the wire.</li>
 * </ol>
 *
 * <p>The observable side effect asserted here is the captured outbound wire
 * payload containing the {@code image_url} data URL whose base64 round-trips to
 * the exact PNG bytes submitted — not an echo back to the client, and not merely
 * that a {@code Content.Image} type exists.</p>
 */
class MultiModalVisionInputDeliveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x62, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
    };

    private String priorEnabled;

    @BeforeEach
    void enableInboundDecoding() {
        priorEnabled = System.getProperty(MultiModalInput.ENABLED_PROPERTY);
        System.setProperty(MultiModalInput.ENABLED_PROPERTY, "true");
    }

    @AfterEach
    void restore() {
        if (priorEnabled == null) {
            System.clearProperty(MultiModalInput.ENABLED_PROPERTY);
        } else {
            System.setProperty(MultiModalInput.ENABLED_PROPERTY, priorEnabled);
        }
    }

    /** Captures the {@link ChatCompletionRequest} the runtime hands the model. */
    private static final class CapturingLlmClient implements LlmClient {
        final AtomicReference<ChatCompletionRequest> captured = new AtomicReference<>();
        final CountDownLatch dispatched = new CountDownLatch(1);

        @Override
        public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
            captured.set(request);
            session.complete();
            dispatched.countDown();
        }
    }

    @Test
    void inboundImageFrameReachesModelAsImageUrlBlock() throws Exception {
        // 1. An inbound multi-modal frame, shaped exactly like the outbound
        //    content envelope the sessions already emit, carrying a base64 PNG.
        var frame = MAPPER.writeValueAsString(java.util.Map.of(
                "type", "content",
                "contentType", "image",
                "mimeType", "image/png",
                "data", Base64.getEncoder().encodeToString(TINY_PNG),
                "text", "Describe this image."));

        // 2. Decode — the production step the @AiEndpoint handler runs.
        var decoded = MultiModalInput.decode(frame);
        assertEquals("Describe this image.", decoded.text());
        assertEquals(1, decoded.parts().size(),
                "the image part must be decoded from the inbound frame");
        assertInstanceOf(Content.Image.class, decoded.parts().get(0));

        // 3. Wire a session over a built-in runtime whose client we capture.
        var client = new CapturingLlmClient();
        var runtime = new org.atmosphere.ai.llm.BuiltInAgentRuntime();
        runtime.configureNativeClient(client);

        var delegate = mock(StreamingSession.class);
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("session-uuid");

        var session = new AiStreamingSession(delegate, runtime,
                "You are helpful.", "gpt-4o", List.of(), resource);

        // 4. The @AiEndpoint handler stashes the decoded parts, then the
        //    @Prompt method's plain session.stream(text) picks them up.
        session.setPendingInputParts(decoded.parts());
        session.stream(decoded.text());

        assertTrue(client.dispatched.await(5, TimeUnit.SECONDS),
                "runtime must dispatch the request to the model client");

        // 5. The captured request must carry the image on its parts...
        var request = client.captured.get();
        assertNotNull(request, "model request must have been captured");
        assertEquals(1, request.parts().size(),
                "the uploaded image must ride the outbound model request, not be echoed to the client");
        assertInstanceOf(Content.Image.class, request.parts().get(0));

        // 6. ...and it must serialize to the OpenAI image_url wire block whose
        //    base64 round-trips to the exact PNG submitted.
        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);
        var messages = json.get("messages");
        var user = messages.get(messages.size() - 1);
        assertEquals("user", user.get("role").stringValue());
        var content = user.get("content");
        assertTrue(content.isArray(),
                "multi-modal user message must be a content array, got " + content);

        var imageBlock = content.get(content.size() - 1);
        assertEquals("image_url", imageBlock.get("type").stringValue(),
                "the outbound model request must contain an image_url block");
        var url = imageBlock.get("image_url").get("url").stringValue();
        assertTrue(url.startsWith("data:image/png;base64,"),
                "image_url must be a base64 data URL, got " + url);
        var roundTripped = Base64.getDecoder().decode(
                url.substring("data:image/png;base64,".length()));
        assertEquals(TINY_PNG.length, roundTripped.length);
        for (int i = 0; i < TINY_PNG.length; i++) {
            assertEquals(TINY_PNG[i], roundTripped[i], "PNG byte " + i + " mismatch on the wire");
        }
    }

    @Test
    void pendingPartsAreConsumedOnceSoTheyDoNotLeakAcrossTurns() throws Exception {
        var client = new CapturingLlmClient();
        var runtime = new org.atmosphere.ai.llm.BuiltInAgentRuntime();
        runtime.configureNativeClient(client);
        var delegate = mock(StreamingSession.class);
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("session-uuid");
        var session = new AiStreamingSession(delegate, runtime,
                "sys", "gpt-4o", List.of(), resource);

        session.setPendingInputParts(List.of(new Content.Image(TINY_PNG, "image/png")));
        session.stream("first turn with image");
        assertTrue(client.dispatched.await(5, TimeUnit.SECONDS));
        assertEquals(1, client.captured.get().parts().size(),
                "first turn must carry the image");

        // A follow-up text turn must NOT re-attach the prior image.
        var second = new CapturingLlmClient();
        runtime.configureNativeClient(second);
        session.stream("second turn, text only");
        assertTrue(second.dispatched.await(5, TimeUnit.SECONDS));
        assertTrue(second.captured.get().parts().isEmpty(),
                "one-shot: the image must not leak into a subsequent turn");
    }

    @Test
    void disabledGateLeavesFrameAsPlainTextPrompt() {
        System.clearProperty(MultiModalInput.ENABLED_PROPERTY);
        var frame = "{\"type\":\"content\",\"contentType\":\"image\","
                + "\"mimeType\":\"image/png\",\"data\":\""
                + Base64.getEncoder().encodeToString(TINY_PNG) + "\",\"text\":\"hi\"}";
        var decoded = MultiModalInput.decode(frame);
        assertTrue(decoded.parts().isEmpty(),
                "with the gate off the frame must pass through untouched (no parts)");
        assertEquals(frame, decoded.text(),
                "with the gate off the raw frame is the prompt (byte-identical legacy path)");
    }

    @Test
    void oversizedImageIsRejectedNotSilentlyDropped() {
        var priorMax = System.getProperty(MultiModalInput.MAX_BYTES_PROPERTY);
        System.setProperty(MultiModalInput.MAX_BYTES_PROPERTY, "8");
        try {
            var frame = "{\"type\":\"content\",\"contentType\":\"image\","
                    + "\"mimeType\":\"image/png\",\"data\":\""
                    + Base64.getEncoder().encodeToString(TINY_PNG)
                    + "\",\"text\":\"describe\"}";
            var decoded = MultiModalInput.decode(frame);
            assertTrue(decoded.parts().isEmpty(),
                    "a part over the byte ceiling must be rejected, not accepted");
            assertEquals("describe", decoded.text(),
                    "the text prompt still dispatches when the oversized part is rejected");
            assertFalse(TINY_PNG.length <= 8, "sanity: the PNG exceeds the 8-byte ceiling");
        } finally {
            if (priorMax == null) {
                System.clearProperty(MultiModalInput.MAX_BYTES_PROPERTY);
            } else {
                System.setProperty(MultiModalInput.MAX_BYTES_PROPERTY, priorMax);
            }
        }
    }

    private static String invokeBuildRequestBody(ChatCompletionRequest request) throws Exception {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://example.invalid")
                .apiKey("test")
                .build();
        Method m = OpenAiCompatibleClient.class.getDeclaredMethod(
                "buildRequestBody", ChatCompletionRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(client, request);
    }
}
