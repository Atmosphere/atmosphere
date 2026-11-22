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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A session's frames reach the broadcaster in {@code seq} order when several
 * threads emit at once. The client drops a frame whose {@code seq} is not above
 * the last one it received, as a duplicate. Two agents a coordinator fans out
 * with {@code parallel()} emit their steps on their own threads: a frame
 * numbered first but queued second was lost, an agent's {@code completed} step
 * among them, which left the Console's activity strip showing it
 * {@code thinking} after the turn ended.
 *
 * <p>The broadcaster below holds the first frame while a second thread emits,
 * the window in which the second frame used to be numbered and queued first.</p>
 */
class StreamingFrameOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void framesOfADefaultSessionAreQueuedInSeqOrder() throws Exception {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.uuid()).thenReturn("uuid-order");
        var session = new DefaultStreamingSession("order-default", resource);
        try {
            assertQueuedInSeqOrder(session, recorder -> when(broadcaster.broadcast(any(), anySet()))
                    .thenAnswer(inv -> recorder.apply(inv.getArgument(0))));
        } finally {
            session.complete();
        }
    }

    @Test
    void framesOfATopicSessionAreQueuedInSeqOrder() throws Exception {
        var broadcaster = mock(Broadcaster.class);
        var session = StreamingSessions.start("order-topic", broadcaster);
        try {
            assertQueuedInSeqOrder(session, recorder -> when(broadcaster.broadcast(any()))
                    .thenAnswer(inv -> recorder.apply(inv.getArgument(0))));
        } finally {
            session.complete();
        }
    }

    private void assertQueuedInSeqOrder(StreamingSession session,
                                        Consumer<Function<Object, Object>> stubBroadcast) throws Exception {
        var queued = Collections.synchronizedList(new ArrayList<Long>());
        var firstInBroadcast = new CountDownLatch(1);
        var secondQueued = new CountDownLatch(1);
        stubBroadcast.accept(message -> {
            var json = message instanceof RawMessage raw ? String.valueOf(raw.message()) : String.valueOf(message);
            var seq = MAPPER.readTree(json).get("seq").asLong();
            if (seq == 1) {
                firstInBroadcast.countDown();
                try {
                    // Hold the first frame. Unfixed, the second thread numbers and
                    // queues its frame meanwhile; fixed, it waits for this one.
                    secondQueued.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            queued.add(seq);
            if (seq == 2) {
                secondQueued.countDown();
            }
            return null;
        });

        var first = Thread.startVirtualThread(() -> session.emit(agentStep("finance-agent", "completed")));
        assertTrue(firstInBroadcast.await(5, TimeUnit.SECONDS), "the first frame reached the broadcaster");
        var second = Thread.startVirtualThread(() -> session.emit(agentStep("strategy-agent", "completed")));
        first.join(5_000);
        second.join(5_000);

        assertEquals(List.of(1L, 2L), List.copyOf(queued),
                "frames must be queued in seq order, or the client drops the one queued late");
    }

    private static AiEvent agentStep(String agent, String step) {
        return new AiEvent.AgentStep(step, "Agent '" + agent + "' " + step, Map.of("agent", agent));
    }
}
