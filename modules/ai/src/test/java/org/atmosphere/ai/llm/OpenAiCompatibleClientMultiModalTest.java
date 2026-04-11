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

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.ai.Content;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the built-in {@link OpenAiCompatibleClient} encodes multi-modal
 * {@link org.atmosphere.ai.Content} parts into the OpenAI chat completions
 * multi-content array format. Without this, image parts passed via
 * {@code context.parts()} would silently drop off the last user message.
 *
 * <p>The OpenAI wire format for a user message with an image is:</p>
 * <pre>{@code
 * {
 *   "role": "user",
 *   "content": [
 *     {"type": "text", "text": "what's in this image?"},
 *     {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBOR..."}}
 *   ]
 * }
 * }</pre>
 *
 * <p>Messages on text-only paths continue to use the plain-string {@code content}
 * form — the multi-content replacement only fires on the last user message and
 * only when {@code request.parts()} is non-empty.</p>
 */
class OpenAiCompatibleClientMultiModalTest {

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

    @SuppressWarnings("unchecked")
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

    @Test
    void textOnlyRequestUsesPlainStringContent() throws Exception {
        var request = ChatCompletionRequest.builder("gpt-4o")
                .system("You are helpful")
                .user("What is 2 + 2?")
                .build();

        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);
        var messages = json.get("messages");
        assertEquals(2, messages.size());

        var user = messages.get(1);
        assertEquals("user", user.get("role").stringValue());
        assertTrue(user.get("content").isString(),
                "Text-only path must emit content as a plain string, got " + user.get("content"));
        assertEquals("What is 2 + 2?", user.get("content").stringValue());
    }

    @Test
    void multiModalRequestEmitsContentArrayWithImageUrl() throws Exception {
        var image = new Content.Image(TINY_PNG, "image/png");
        var request = ChatCompletionRequest.builder("gpt-4o")
                .system("You are helpful")
                .user("Describe this image.")
                .parts(List.of(image))
                .build();

        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);
        var messages = json.get("messages");
        assertEquals(2, messages.size());

        // System message is unchanged.
        var system = messages.get(0);
        assertEquals("system", system.get("role").stringValue());
        assertTrue(system.get("content").isString());

        // User message is now a content array with text + image_url.
        var user = messages.get(1);
        assertEquals("user", user.get("role").stringValue());
        var content = user.get("content");
        assertTrue(content.isArray(),
                "Multi-modal path must emit content as a JSON array, got " + content);
        assertEquals(2, content.size());

        var textBlock = content.get(0);
        assertEquals("text", textBlock.get("type").stringValue());
        assertEquals("Describe this image.", textBlock.get("text").stringValue());

        var imageBlock = content.get(1);
        assertEquals("image_url", imageBlock.get("type").stringValue());
        var url = imageBlock.get("image_url").get("url").stringValue();
        assertNotNull(url);
        assertTrue(url.startsWith("data:image/png;base64,"),
                "image_url must be a base64 data URL, got " + url);

        // The base64 payload must round-trip to the original PNG bytes.
        var base64 = url.substring("data:image/png;base64,".length());
        var decoded = java.util.Base64.getDecoder().decode(base64);
        assertInstanceOf(byte[].class, decoded);
        assertEquals(TINY_PNG.length, decoded.length);
        for (int i = 0; i < TINY_PNG.length; i++) {
            assertEquals(TINY_PNG[i], decoded[i], "byte " + i + " mismatch");
        }
    }

    @Test
    void multiModalRequestWithAudioEmitsInputAudioBlock() throws Exception {
        var audio = new Content.Audio(new byte[]{1, 2, 3, 4}, "audio/mp3");
        var request = ChatCompletionRequest.builder("gpt-4o-audio-preview")
                .user("What do you hear?")
                .parts(List.of(audio))
                .build();

        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);
        var user = json.get("messages").get(0);
        var content = user.get("content");
        assertTrue(content.isArray());

        var audioBlock = content.get(1);
        assertEquals("input_audio", audioBlock.get("type").stringValue());
        var inputAudio = audioBlock.get("input_audio");
        assertEquals("mp3", inputAudio.get("format").stringValue());
        assertNotNull(inputAudio.get("data"));
    }
}
