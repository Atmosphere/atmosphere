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

    private static List<String> payloads(List<RunEvent> events) {
        return events.stream().map(RunEvent::payload).toList();
    }
}
