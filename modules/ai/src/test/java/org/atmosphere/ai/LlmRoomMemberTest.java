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
import org.atmosphere.room.Room;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LlmRoomMemberTest {

    private final LlmClient client = mock(LlmClient.class);
    private final Room room = mock(Room.class);

    @Test
    void constructorRejectsNullId() {
        assertThrows(NullPointerException.class,
                () -> new LlmRoomMember(null, client, "gpt-4"));
    }

    @Test
    void constructorRejectsNullClient() {
        assertThrows(NullPointerException.class,
                () -> new LlmRoomMember("bot", null, "gpt-4"));
    }

    @Test
    void constructorRejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new LlmRoomMember("bot", client, null));
    }

    @Test
    void idReturnsConstructorValue() {
        var member = new LlmRoomMember("assistant", client, "gpt-4");
        assertEquals("assistant", member.id());
    }

    @Test
    void metadataContainsTypeAndModel() {
        var member = new LlmRoomMember("bot", client, "gpt-4o");
        assertEquals(Map.of("type", "llm", "model", "gpt-4o"), member.metadata());
    }

    @Test
    void onMessageSkipsOwnMessages() {
        var member = new LlmRoomMember("bot", client, "gpt-4");
        member.onMessage(room, "bot", "hello");
        verify(client, never()).streamChatCompletion(
                any(ChatCompletionRequest.class), any(StreamingSession.class));
    }

    @Test
    void onMessageSkipsBlankMessages() {
        var member = new LlmRoomMember("bot", client, "gpt-4");
        member.onMessage(room, "user1", "   ");
        verify(client, never()).streamChatCompletion(
                any(ChatCompletionRequest.class), any(StreamingSession.class));
    }

    @Test
    void onMessageStreamsAndBroadcastsResponse() throws Exception {
        var latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            StreamingSession session = invocation.getArgument(1);
            session.send("Hello ");
            session.send("world");
            session.complete();
            latch.countDown();
            return null;
        }).when(client).streamChatCompletion(
                any(ChatCompletionRequest.class), any(StreamingSession.class));

        var member = new LlmRoomMember("bot", client, "gpt-4");
        member.onMessage(room, "user1", "Hi there");

        latch.await(5, TimeUnit.SECONDS);
        // Allow virtual thread to finish broadcasting
        Thread.sleep(100);

        var reqCaptor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(client).streamChatCompletion(reqCaptor.capture(), any(StreamingSession.class));
        assertEquals("gpt-4", reqCaptor.getValue().model());
        verify(room).broadcast("Hello world");
    }

    @Test
    void onMessageUsesCustomSystemPrompt() throws Exception {
        var latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            StreamingSession session = invocation.getArgument(1);
            session.complete("Done");
            latch.countDown();
            return null;
        }).when(client).streamChatCompletion(
                any(ChatCompletionRequest.class), any(StreamingSession.class));

        var member = new LlmRoomMember("bot", client, "gpt-4", "Be concise");
        member.onMessage(room, "user1", "Question");

        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(100);

        var reqCaptor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(client).streamChatCompletion(reqCaptor.capture(), any(StreamingSession.class));
        var messages = reqCaptor.getValue().messages();
        assertEquals("system", messages.get(0).role());
        assertEquals("Be concise", messages.get(0).content());
    }

    @Test
    void completeWithSummaryBroadcastsSummary() throws Exception {
        var latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            StreamingSession session = invocation.getArgument(1);
            session.send("partial");
            session.complete("final answer");
            latch.countDown();
            return null;
        }).when(client).streamChatCompletion(
                any(ChatCompletionRequest.class), any(StreamingSession.class));

        var member = new LlmRoomMember("bot", client, "gpt-4");
        member.onMessage(room, "user1", "test");

        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(100);

        verify(room).broadcast("final answer");
    }
}
