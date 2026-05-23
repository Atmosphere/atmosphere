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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.Content;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the Content.Image → Spring AI Media wire shape for the Spring AI
 * Alibaba runtime. The contract test {@link SpringAiAlibabaRuntimeContractTest}
 * asserts that {@code VISION} is declared in capabilities; this test
 * asserts the matching code path actually rebuilds the trailing
 * {@link UserMessage} with a {@link Media} attachment carrying the
 * expected mime type + byte payload.
 *
 * <p>Closes the gap surfaced in
 * {@code docs/audits/vision-parity-2026-05-22.md}: SPI presence
 * (capability declaration) is not the same as runtime presence (wire-
 * level translation actually firing).</p>
 */
class SpringAiAlibabaVisionWireShapeTest {

    @Test
    void contentImageAttachesAsMediaOnTrailingUserMessage() throws Exception {
        var agent = mock(ReactAgent.class);
        when(agent.call(anyList())).thenReturn(new AssistantMessage("ok"));
        var runtime = new SpringAiAlibabaRuntimeContractTest.TestableSpringAiAlibabaRuntime(agent);

        var pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic
        var context = new AgentExecutionContext(
                "What is in this image?", "You are helpful", "qwen-vl-plus",
                null, "session-vis", "user-1", "conv-vis",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(),
                List.of(Content.image(pngBytes, "image/png")),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());

        runtime.execute(context, new CollectingSession());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(agent).call(captor.capture());
        var dispatched = captor.getValue();
        assertNotNull(dispatched, "ReactAgent must receive a message list");
        assertTrue(!dispatched.isEmpty(), "must contain at least the user turn");

        Message trailing = dispatched.get(dispatched.size() - 1);
        assertTrue(trailing instanceof UserMessage,
                "trailing message must be a UserMessage (got " + trailing.getClass().getSimpleName() + ")");
        UserMessage userMsg = (UserMessage) trailing;
        assertEquals("What is in this image?", userMsg.getText(),
                "user text must round-trip into the rebuilt UserMessage");

        List<Media> media = userMsg.getMedia();
        assertNotNull(media, "UserMessage.getMedia() must not be null");
        assertEquals(1, media.size(), "exactly one Media attachment expected");
        var attached = media.get(0);
        assertEquals("image/png", attached.getMimeType().toString(),
                "mime type must propagate to Spring AI Media");

        // Defensive: read the bytes back and assert they match the original
        // Content.Image payload. This catches a subtle defensive-copy or
        // stream-handling bug that the mime-type assertion alone would miss.
        var roundTripped = attached.getDataAsByteArray();
        assertEquals(pngBytes.length, roundTripped.length, "byte payload length must match");
        for (int i = 0; i < pngBytes.length; i++) {
            assertEquals(pngBytes[i], roundTripped[i],
                    "byte " + i + " must propagate to Media without corruption");
        }
    }
}
