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
package org.atmosphere.ai.resume;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each event of a run is handed to a consumer once, so a long-polling client
 * that re-sends the run id on every poll gets the run in pieces, not the same
 * replay on every poll.
 */
class RunEventReplayBufferPositionsTest {

    @Test
    void claimNewHandsEachEventToAConsumerOnce() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("streaming-text", "a");
        buffer.capture("streaming-text", "b");

        assertEquals(List.of("a", "b"), payloads(buffer.claimNew("poll-1")));
        assertTrue(buffer.claimNew("poll-1").isEmpty(), "nothing new: nothing handed again");

        buffer.capture("complete", "done");
        assertEquals(List.of("done"), payloads(buffer.claimNew("poll-1")));
        assertEquals(List.of("a", "b", "done"), payloads(buffer.claimNew("poll-2")),
                "another consumer gets every retained event");
    }

    @Test
    void claimTakesAnEventOnceAndNotOneClaimNewHandedOver() {
        var buffer = new RunEventReplayBuffer();
        var first = buffer.capture("streaming-text", "a");
        buffer.claimNew("poll-1");
        var second = buffer.capture("streaming-text", "b");

        assertFalse(buffer.claim("poll-1", first), "already handed by claimNew");
        assertTrue(buffer.claim("poll-1", second));
        assertFalse(buffer.claim("poll-1", second), "an event is claimed once");
        assertTrue(buffer.claimNew("poll-1").isEmpty());
    }

    @Test
    void unclaimGivesBackEventsThatNeverReachedTheConsumer() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("streaming-text", "a");
        var lost = buffer.capture("streaming-text", "b");
        buffer.claimNew("poll-1");

        buffer.unclaim("poll-1", lost.sequence());

        assertEquals(List.of("b"), payloads(buffer.claimNew("poll-1")));
    }

    @Test
    void positionsAreBoundedAndTheLeastRecentlyUsedIsForgotten() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("streaming-text", "a");
        for (int i = 0; i <= RunEventReplayBuffer.MAX_REPLAY_POSITIONS; i++) {
            buffer.claimNew("poll-" + i);
        }

        assertEquals(List.of("a"), payloads(buffer.claimNew("poll-0")),
                "past the bound the oldest consumer is forgotten and handed the events again");
        assertTrue(buffer.claimNew("poll-" + RunEventReplayBuffer.MAX_REPLAY_POSITIONS).isEmpty());
    }

    @Test
    void framesReceivedAfterAGapDoNotHideTheEventsTheConsumerMissed() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("streaming-text", "a");
        buffer.capture("streaming-text", "b");
        buffer.capture("streaming-text", "c");
        buffer.capture("complete", "");

        buffer.handed("poll-1", 2, 3);
        assertEquals(List.of("a", "b", "c", ""), payloads(buffer.claimNew("poll-1")),
                "a and b never reached poll-1: it is handed them, and what followed them again");

        buffer.handed("poll-2", 0, 1);
        buffer.handed("poll-2", 2, 3);
        assertTrue(buffer.claimNew("poll-2").isEmpty(), "spans that follow on from each other count whole");
    }

    @Test
    void eventsNoLongerRetainedLeaveNoGap() {
        var buffer = new RunEventReplayBuffer(2);
        buffer.capture("streaming-text", "evicted");
        buffer.capture("streaming-text", "b");
        buffer.capture("streaming-text", "c");

        buffer.handed("poll-1", 1, 2);
        assertTrue(buffer.claimNew("poll-1").isEmpty(), "no replay can hand the evicted event anyway");
    }

    @Test
    void framesOthersReceivedNeverPushOutThePositionOfAConsumerThatClaimed() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("streaming-text", "a");
        buffer.capture("streaming-text", "b");
        buffer.capture("complete", "");
        assertEquals(3, buffer.claimNew("owner").size());

        for (int i = 0; i < RunEventReplayBuffer.MAX_REPLAY_POSITIONS; i++) {
            buffer.handed("member-" + i, 0, 2);
        }

        assertTrue(buffer.claimNew("owner").isEmpty(), "the members that received the run's frames as a reply"
                + " must not make the run forget what it handed the owner");
    }

    @Test
    void pastTheBoundTheConsumerLeastRecentlyServedAReplyIsForgottenFirst() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("streaming-text", "a");
        buffer.capture("streaming-text", "b");
        buffer.capture("complete", "");
        buffer.handed("first", 0, 2);

        for (int i = 0; i < RunEventReplayBuffer.MAX_REPLAY_POSITIONS; i++) {
            buffer.handed("member-" + i, 0, 2);
        }

        assertTrue(buffer.claimNew("member-" + (RunEventReplayBuffer.MAX_REPLAY_POSITIONS - 1)).isEmpty(),
                "a consumer that received every frame lately is remembered");
        assertEquals(3, buffer.claimNew("first").size(), "past the bound, the consumer least recently served"
                + " a reply is forgotten: it is handed the retained events again");
    }

    /**
     * Past the bound, the receipt of the consumer served least recently is
     * forgotten, not the one recorded first: a consumer served again keeps its
     * receipt.
     */
    @Test
    void aConsumerServedAgainKeepsItsReceiptOverOneServedLongerAgo() {
        var buffer = new RunEventReplayBuffer(null, 16, 2, 2);
        buffer.capture("streaming-text", "a");
        buffer.capture("streaming-text", "b");
        buffer.capture("streaming-text", "c");
        buffer.handed("first", 0, 0);
        buffer.handed("second", 0, 0);
        buffer.handed("first", 1, 1);

        buffer.handed("third", 0, 0);

        assertEquals(1, buffer.claimNew("first").size(), "the consumer served again is remembered");
        assertEquals(3, buffer.claimNew("second").size(), "the one served longest ago is forgotten");
    }

    @Test
    void aConsumerGoneForGoodFreesItsPlace() {
        var buffer = new RunEventReplayBuffer(null, 16, 2, 2);
        buffer.capture("streaming-text", "a");
        buffer.capture("streaming-text", "b");
        assertEquals(2, buffer.claimNew("owner").size());
        buffer.handed("member", 0, 1);
        assertEquals(2, buffer.rememberedConsumers());
        assertTrue(buffer.awaitNext("owner", event -> { }).live());
        assertEquals(1, buffer.subscriberCount());

        buffer.forget("member");
        buffer.forget("owner");

        assertEquals(0, buffer.rememberedConsumers(), "a client gone for good takes no place");
        assertEquals(0, buffer.subscriberCount(), "nor a waiting slot");
        assertEquals(2, buffer.claimNew("owner").size(), "one that comes back after all is handed the run again");
    }

    @Test
    void aClaimAfterFramesOthersReceivedPushesOutNoConsumerThatClaimed() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("streaming-text", "a");
        buffer.capture("complete", "");
        assertEquals(2, buffer.claimNew("owner").size());
        for (int i = 0; i < RunEventReplayBuffer.MAX_REPLAY_POSITIONS; i++) {
            buffer.handed("member-" + i, 0, 1);
        }

        assertTrue(buffer.claimNew("member-0").isEmpty(), "member-0 received the run as a reply");
        assertEquals(2, buffer.claimNew("late").size(), "a consumer that received nothing is handed everything");

        assertTrue(buffer.claimNew("owner").isEmpty(), "two consumers claimed, far below the bound: neither the"
                + " members' frames nor their claims may make the run forget the owner");
    }

    @Test
    void framesReceivedAfterAClaimMoveTheConsumersPosition() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("streaming-text", "a");
        assertEquals(1, buffer.claimNew("owner").size());
        for (int i = 0; i < RunEventReplayBuffer.MAX_REPLAY_POSITIONS; i++) {
            buffer.handed("member-" + i, 0, 0);
        }
        buffer.capture("streaming-text", "b");
        buffer.capture("complete", "");

        buffer.handed("owner", 1, 2);

        assertTrue(buffer.claimNew("owner").isEmpty(), "the owner received the rest of the run as a reply");
    }

    private static List<String> payloads(List<RunEvent> events) {
        return events.stream().map(RunEvent::payload).toList();
    }
}
