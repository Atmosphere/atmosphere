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

import org.atmosphere.ai.filter.AiStreamMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run stays within its bounds (events and characters) without losing a
 * normal long reply: past its capacity its consecutive text events are joined,
 * a client holding part of a joined event is replayed the rest of it, and
 * what still has to be dropped is replaced by a notice saying so.
 */
class RunEventReplayBufferBoundsTest {

    private static final String SESSION = "room-session-1";

    /** The room session's frame counter. */
    private final AtomicLong frames = new AtomicLong();

    private RunEventReplayBuffer.Numbering numbering() {
        return new RunEventReplayBuffer.Numbering(SESSION, frames::incrementAndGet);
    }

    private static String text(List<RunEvent> events) {
        var text = new StringBuilder();
        for (var event : events) {
            if ("streaming-text".equals(event.type())) {
                text.append(event.payload());
            }
        }
        return text.toString();
    }

    @Test
    void aReplyOfMoreTextDeltasThanTheCapacityIsRetainedWholeWithItsPrompt() {
        var buffer = new RunEventReplayBuffer("run-1", RunEventReplayBuffer.DEFAULT_CAPACITY);
        buffer.captureIfOpen(AiStreamMessage.PROMPT_TYPE, "Explain triangles", numbering());
        var reply = new StringBuilder();
        for (var i = 0; i < 2_500; i++) {
            var delta = "d" + i + " ";
            reply.append(delta);
            buffer.captureIfOpen("streaming-text", delta, numbering());
        }
        buffer.terminateEvent("complete", "", numbering());

        assertTrue(buffer.size() <= buffer.capacity(), "bounded: " + buffer.size() + " events retained");
        var events = buffer.snapshot();
        assertEquals(AiStreamMessage.PROMPT_TYPE, events.get(0).type(), "the prompt is kept");
        assertEquals(reply.toString(), text(events), "every text delta, in order, once");
        assertEquals(2_502, events.size(), "read back one by one: the prompt, 2,500 deltas, the end");
        assertEquals("complete", events.get(events.size() - 1).type());
        for (var i = 0; i < events.size(); i++) {
            assertEquals(i + 1, buffer.frameOf(events.get(i)), "each event keeps the frame it was written in");
            assertEquals(i, events.get(i).sequence(), "and its own sequence");
        }
    }

    @Test
    void aClientHoldingSomeOfTheJoinedDeltasIsReplayedTheOthers() {
        var buffer = new RunEventReplayBuffer("run-1", 8);
        buffer.captureIfOpen(AiStreamMessage.PROMPT_TYPE, "p", numbering());
        for (var i = 0; i < 20; i++) {
            buffer.captureIfOpen("streaming-text", "t" + i + ",", numbering());
        }
        assertTrue(buffer.size() < 8, "the deltas were joined");
        // Frames: the prompt 1, the deltas t0..t19 frames 2..21; the client holds up to t9 (frame 11),
        // and a replay writes what is numbered past it (RunReattachSupport, RoomCatchUp).
        var rest = new StringBuilder();
        for (var event : buffer.snapshot()) {
            if (buffer.frameOf(event) > 11) {
                rest.append(event.payload());
            }
        }
        assertEquals("t10,t11,t12,t13,t14,t15,t16,t17,t18,t19,", rest.toString());
    }

    @Test
    void aLongPollingConsumerHandedPartOfAJoinedEventIsHandedTheRest() {
        var buffer = new RunEventReplayBuffer("run-1", 8);
        buffer.captureIfOpen(AiStreamMessage.PROMPT_TYPE, "p", numbering());
        for (var i = 0; i < 5; i++) {
            buffer.captureIfOpen("streaming-text", "t" + i + ",", numbering());
        }
        assertEquals(6, buffer.claimNew("poll").size(), "the first poll is handed the prompt and t0..t4");
        for (var i = 5; i < 20; i++) {
            buffer.captureIfOpen("streaming-text", "t" + i + ",", numbering());
        }

        var next = buffer.claimNew("poll");

        assertEquals("t5,t6,t7,t8,t9,t10,t11,t12,t13,t14,t15,t16,t17,t18,t19,", text(next),
                "the text it was not handed, none of what it was");
        assertTrue(buffer.claimNew("poll").isEmpty());
    }

    @Test
    void aReplayFromASequenceInsideAJoinedEventStartsThere() {
        var buffer = new RunEventReplayBuffer("run-1", 4);
        for (var i = 0; i < 10; i++) {
            buffer.captureIfOpen("streaming-text", "t" + i + ",", numbering());
        }
        assertEquals("t6,t7,t8,t9,", text(buffer.replayFrom(6)));
    }

    @Test
    void whatTheBoundsStillDropIsReplacedByANoticeUnderTheLastFrameDropped() {
        var buffer = new RunEventReplayBuffer("run-1", 64, 300);
        buffer.captureIfOpen(AiStreamMessage.PROMPT_TYPE, "p", numbering());
        var reply = new StringBuilder();
        for (var i = 0; i < 200; i++) {
            var delta = "delta-" + i + " ";
            reply.append(delta);
            buffer.captureIfOpen("streaming-text", delta, numbering());
        }

        var events = buffer.snapshot();
        assertEquals(AiStreamMessage.PROMPT_TYPE, events.get(0).type(), "the prompt is never dropped");
        var notice = events.get(1);
        assertEquals(RunEventReplayBuffer.TRUNCATED_NOTICE, notice.payload(), "the replay says the reply is not whole");
        var kept = text(events.subList(2, events.size()));
        assertTrue(reply.toString().endsWith(kept), "the newest text is kept, whole from where it starts");
        assertTrue(kept.length() < reply.length(), "something was dropped");
        var chars = 0;
        for (var event : events) {
            chars += event.payload().length();
        }
        assertTrue(chars <= 300, "within the character bound: " + chars);
        // The notice stands under the frame of the last delta dropped: just before the first one kept.
        var firstKept = reply.length() - kept.length();
        var droppedDeltas = reply.substring(0, firstKept).split(" ").length;
        assertEquals(1 + droppedDeltas, buffer.frameOf(notice),
                "a client that missed what was dropped is told; one that holds it drops the notice as held");
        assertTrue(buffer.frameOf(notice) < buffer.frameOf(events.get(2)), "the notice comes before what is kept");
    }

    @Test
    void aBufferWhoseFramesAreNotNumberedDropsItsOldestEventsAsBefore() {
        var buffer = new RunEventReplayBuffer(3);
        buffer.capture("t", "a");
        buffer.capture("t", "b");
        buffer.capture("t", "c");
        buffer.capture("t", "d");

        assertEquals(List.of("b", "c", "d"), buffer.snapshot().stream().map(RunEvent::payload).toList());
    }
}
