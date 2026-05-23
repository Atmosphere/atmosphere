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

import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import com.microsoft.semantickernel.services.chatcompletion.ChatHistory;
import com.microsoft.semantickernel.services.chatcompletion.StreamingChatContent;
import com.microsoft.semantickernel.services.chatcompletion.message.ChatMessageImageContent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.Content;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the Content.Image → SK ChatMessageImageContent wire shape for
 * {@link SemanticKernelAgentRuntime}. The contract test asserts that
 * {@code VISION} is declared in capabilities; this test asserts the
 * matching code path actually appends a {@link ChatMessageImageContent}
 * to the {@link ChatHistory} that SK's chat-completion service receives,
 * so the runtime claim survives a refactor of
 * {@code buildChatHistory}.
 *
 * <p>Closes the gap surfaced in
 * {@code docs/audits/vision-parity-2026-05-22.md}: SPI presence
 * (capability declaration) is not the same as runtime presence (wire-
 * level translation actually firing).</p>
 */
class SemanticKernelVisionWireShapeTest {

    @Test
    @SuppressWarnings("unchecked")
    void contentImageAppendsChatMessageImageContentToHistory() {
        var frame = (StreamingChatContent<Object>) mock(StreamingChatContent.class);
        when(frame.getContent()).thenReturn("ok");
        when(frame.getMetadata()).thenReturn(null);

        var service = mock(ChatCompletionService.class);
        when(service.getStreamingChatMessageContentsAsync(
                any(ChatHistory.class), any(), any()))
                .thenReturn(Flux.just(frame));

        var runtime = new SemanticKernelRuntimeContractTest.TestableSemanticKernelRuntime(service);

        var pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic
        var context = new AgentExecutionContext(
                "What is in this image?", "You are helpful", "gpt-4o",
                null, "session-vis", "user-1", "conv-vis",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(),
                List.of(Content.image(pngBytes, "image/png")),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());

        runtime.execute(context, new CollectingSession());

        var captor = ArgumentCaptor.forClass(ChatHistory.class);
        verify(service).getStreamingChatMessageContentsAsync(
                captor.capture(), any(), any());
        var history = captor.getValue();
        assertNotNull(history, "ChatCompletionService must receive a ChatHistory");

        // Walk every message in the history looking for an image entry.
        // SK Java's ChatMessageImageContent is its own ChatMessageContent
        // appended after the text user-message, so the user turn ends up
        // as two distinct ChatHistory entries: the text first, then the image.
        ChatMessageImageContent<?> imageMessage = null;
        for (var msg : history.getMessages()) {
            if (msg instanceof ChatMessageImageContent<?> img) {
                imageMessage = img;
                break;
            }
        }
        assertNotNull(imageMessage,
                "ChatHistory must carry a ChatMessageImageContent entry "
                        + "for the supplied Content.Image part");
        // SK's withImage(mime, byte[]) sets the content to a data: URI under
        // the hood. Assert the prefix so a future SDK switch to a non-data-URI
        // representation (e.g. blob ID) breaks the test loudly.
        var encoded = imageMessage.getContent();
        assertNotNull(encoded, "image message must have a non-null content payload");
        assertTrue(encoded.startsWith("data:image/png;base64,"),
                "expected base64 data URI prefix, got: "
                        + encoded.substring(0, Math.min(40, encoded.length())));
        // PNG magic bytes (89 50 4E 47) base64-encode to "iVBORw==" — pin
        // the exact suffix so a defensive-copy bug or mime-type mix-up cannot
        // pass silently.
        assertTrue(encoded.endsWith("iVBORw=="),
                "expected encoded payload to end with iVBORw==, got tail: "
                        + encoded.substring(Math.max(0, encoded.length() - 16)));
    }
}
