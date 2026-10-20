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

import org.atmosphere.ai.ExecutionHandle;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The prompt envelope carrying a client's run id, and the one-run-per-id registration it relies on. */
class ClientRunIdsTest {

    private static final String ID = "3f2c1a9e-4b7d-4c1e-9a2b-0f6e5d4c3b2a";

    @Test
    void anEnvelopeYieldsItsPromptAndItsId() {
        var unwrapped = ClientRunIds.unwrap("{\"clientRunId\":\"" + ID + "\",\"prompt\":\"what is 2+2?\"}");
        assertNotNull(unwrapped);
        assertEquals("what is 2+2?", unwrapped.prompt());
        assertEquals(ID, unwrapped.runId());
    }

    @Test
    void aJsonPromptInsideTheEnvelopeIsHandedOnAsJson() {
        var unwrapped = ClientRunIds.unwrap(
                "{\"clientRunId\":\"" + ID + "\",\"prompt\":{\"prompt\":\"hi\",\"hints\":{\"maxCost\":1}}}");
        assertNotNull(unwrapped);
        assertEquals("{\"prompt\":\"hi\",\"hints\":{\"maxCost\":1}}", unwrapped.prompt());
    }

    @Test
    void aMalformedIdLeavesThePromptWithoutAnId() {
        var unwrapped = ClientRunIds.unwrap("{\"clientRunId\":\"../../etc\",\"prompt\":\"hi\"}");
        assertNotNull(unwrapped);
        assertEquals("hi", unwrapped.prompt());
        assertNull(unwrapped.runId());
    }

    @Test
    void aBodyThatIsNoEnvelopeIsThePromptItself() {
        assertNull(ClientRunIds.unwrap("what is 2+2?"));
        assertNull(ClientRunIds.unwrap("{\"type\":\"content\",\"text\":\"hi\"}"));
        // An application's own JSON naming the field among others is left alone.
        assertNull(ClientRunIds.unwrap("{\"clientRunId\":\"" + ID + "\",\"prompt\":\"hi\",\"extra\":1}"));
        assertNull(ClientRunIds.unwrap("{\"clientRunId\":\"" + ID + "\"}"));
    }

    @Test
    void anIdNamesOneRunHoweverOftenItIsRegistered() {
        var registry = new RunRegistry();
        var id = UUID.randomUUID().toString();
        var first = registry.registerIfAbsent("agent", "anonymous", "a", new ExecutionHandle.Settable(() -> { }),
                new RunEventReplayBuffer(), id);
        assertNotNull(first);
        assertNull(registry.registerIfAbsent("agent", "anonymous", "b", new ExecutionHandle.Settable(() -> { }),
                new RunEventReplayBuffer(), id), "a live run keeps its id");
        assertSame(first, registry.lookup(id).orElseThrow());

        ((ExecutionHandle.Settable) first.executionHandle()).complete();
        assertNull(registry.registerIfAbsent("agent", "anonymous", "c", new ExecutionHandle.Settable(() -> { }),
                new RunEventReplayBuffer(), id), "a finished run still replayable keeps its id");
    }
}
