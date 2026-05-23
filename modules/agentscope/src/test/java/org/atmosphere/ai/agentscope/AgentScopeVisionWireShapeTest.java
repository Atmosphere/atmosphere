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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.Content;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the Content.Image → AgentScope ImageBlock(Base64Source) wire shape
 * for {@link AgentScopeAgentRuntime}. The contract test asserts that
 * {@code VISION} is declared in capabilities; this test asserts the
 * matching code path actually rebuilds the trailing {@link Msg} with a
 * native {@link ImageBlock} carrying the expected mime type + base64
 * payload, so the runtime claim survives a refactor of
 * {@code attachPartsToTrailingUserMessage}.
 *
 * <p>Closes the gap surfaced in
 * {@code docs/audits/vision-parity-2026-05-22.md}: SPI presence
 * (capability declaration) is not the same as runtime presence (wire-
 * level translation actually firing).</p>
 */
class AgentScopeVisionWireShapeTest {

    @Test
    void contentImageBecomesImageBlockOnTrailingUserMsg() {
        var agent = mock(ReActAgent.class);
        when(agent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.empty());

        var runtime = new TestableAgentScopeRuntime(agent);

        var pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic
        var expectedBase64 = Base64.getEncoder().encodeToString(pngBytes);
        var context = new AgentExecutionContext(
                "What is in this image?", "You are helpful", "qwen-vl-plus",
                null, "session-vis", "user-1", "conv-vis",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(),
                List.of(Content.image(pngBytes, "image/png")),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());

        var session = new CollectingSession();
        runtime.execute(context, session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(agent).stream(captor.capture(), any(StreamOptions.class));
        var dispatched = captor.getValue();
        assertNotNull(dispatched, "ReActAgent.stream must receive a Msg list");
        assertTrue(!dispatched.isEmpty(), "must contain at least the user turn");

        Msg trailing = dispatched.get(dispatched.size() - 1);
        assertEquals(MsgRole.USER, trailing.getRole(),
                "trailing message must be USER (got " + trailing.getRole() + ")");

        var blocks = trailing.getContent();
        assertNotNull(blocks, "trailing Msg must carry a content block list");
        // Expected: TextBlock("What is in this image?") + ImageBlock(Base64Source("image/png", <base64>))
        assertEquals(2, blocks.size(),
                "expected exactly two ContentBlocks (text + image), got " + blocks.size());
        assertTrue(blocks.get(0) instanceof TextBlock,
                "first block must be TextBlock (got " + blocks.get(0).getClass().getSimpleName() + ")");
        assertEquals("What is in this image?", ((TextBlock) blocks.get(0)).getText(),
                "user text must round-trip into the rebuilt Msg");
        assertTrue(blocks.get(1) instanceof ImageBlock,
                "second block must be ImageBlock (got " + blocks.get(1).getClass().getSimpleName() + ")");
        var source = ((ImageBlock) blocks.get(1)).getSource();
        assertTrue(source instanceof Base64Source,
                "ImageBlock source must be Base64Source (got " + source.getClass().getSimpleName() + ")");
        var b64 = (Base64Source) source;
        assertEquals("image/png", b64.getMediaType(), "mime type must propagate");
        assertEquals(expectedBase64, b64.getData(),
                "Base64Source data must be the base64-encoded original payload, byte-exact");
    }

    /** Test subclass that injects the mocked agent directly. */
    static class TestableAgentScopeRuntime extends AgentScopeAgentRuntime {
        TestableAgentScopeRuntime(ReActAgent agent) {
            setNativeClient(agent);
        }
    }
}
