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
package org.atmosphere.ai.filter;

import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CostMeteringFilterTest {

    private final CostMeteringFilter filter = new CostMeteringFilter();

    private BroadcastAction sendToken(String broadcasterId, String sessionId, long seq) {
        var msg = new AiStreamMessage("token", "word", sessionId, seq, null, null);
        var raw = new RawMessage(msg.toJson());
        return filter.filter(broadcasterId, raw, raw);
    }

    private BroadcastAction sendComplete(String broadcasterId, String sessionId, long seq) {
        var msg = new AiStreamMessage("complete", null, sessionId, seq, null, null);
        var raw = new RawMessage(msg.toJson());
        return filter.filter(broadcasterId, raw, raw);
    }

    @Test
    public void testCountsTokensPerSession() {
        sendToken("b1", "s1", 1);
        sendToken("b1", "s1", 2);
        sendToken("b1", "s1", 3);

        assertEquals(3, filter.getSessionTokenCount("s1"));
    }

    @Test
    public void testCountsTokensPerBroadcaster() {
        sendToken("b1", "s1", 1);
        sendToken("b1", "s1", 2);
        sendToken("b1", "s2", 1);

        assertEquals(3, filter.getBroadcasterTokenCount("b1"));
    }

    @Test
    public void testSessionCountCleansUpOnComplete() {
        sendToken("b1", "s1", 1);
        sendToken("b1", "s1", 2);
        assertEquals(2, filter.getSessionTokenCount("s1"));

        sendComplete("b1", "s1", 3);
        assertEquals(0, filter.getSessionTokenCount("s1"));
    }

    @Test
    public void testBroadcasterCountPersistsAcrossSessions() {
        sendToken("b1", "s1", 1);
        sendToken("b1", "s1", 2);
        sendComplete("b1", "s1", 3);

        sendToken("b1", "s2", 1);
        assertEquals(3, filter.getBroadcasterTokenCount("b1"));
    }

    @Test
    public void testBudgetEnforcement() throws Exception {
        filter.setBudget("b1", 3);

        // First 3 tokens pass through
        assertEquals(BroadcastAction.ACTION.CONTINUE, sendToken("b1", "s1", 1).action());
        assertEquals(BroadcastAction.ACTION.CONTINUE, sendToken("b1", "s1", 2).action());
        assertEquals(BroadcastAction.ACTION.CONTINUE, sendToken("b1", "s1", 3).action());

        // 4th token exceeds budget — should SKIP with error
        var result = sendToken("b1", "s1", 4);
        assertEquals(BroadcastAction.ACTION.SKIP, result.action());

        var raw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) raw.message());
        assertTrue(parsed.isError());
        assertTrue(parsed.data().contains("budget exceeded"));
    }

    @Test
    public void testNoBudgetMeansNoLimit() {
        for (int i = 1; i <= 1000; i++) {
            assertEquals(BroadcastAction.ACTION.CONTINUE, sendToken("b1", "s1", i).action());
        }
        assertEquals(1000, filter.getBroadcasterTokenCount("b1"));
    }

    @Test
    public void testResetBroadcasterCount() {
        sendToken("b1", "s1", 1);
        sendToken("b1", "s1", 2);
        assertEquals(2, filter.getBroadcasterTokenCount("b1"));

        filter.resetBroadcasterCount("b1");
        assertEquals(0, filter.getBroadcasterTokenCount("b1"));
    }

    @Test
    public void testRemoveBudget() {
        filter.setBudget("b1", 2);
        sendToken("b1", "s1", 1);
        sendToken("b1", "s1", 2);

        // Remove budget — should no longer enforce
        filter.removeBudget("b1");
        assertEquals(BroadcastAction.ACTION.CONTINUE, sendToken("b1", "s1", 3).action());
    }

    @Test
    public void testBudgetAppliesAcrossSessions() throws Exception {
        filter.setBudget("b1", 3);

        sendToken("b1", "s1", 1);
        sendToken("b1", "s1", 2);
        sendComplete("b1", "s1", 3);

        // Session s1 used 2 tokens. s2 can use 1 more before budget.
        assertEquals(BroadcastAction.ACTION.CONTINUE, sendToken("b1", "s2", 1).action());

        // 4th total token — exceeds budget
        var result = sendToken("b1", "s2", 2);
        assertEquals(BroadcastAction.ACTION.SKIP, result.action());
    }

    @Test
    public void testPassesThroughMetadata() {
        var msg = new AiStreamMessage("metadata", null, "s1", 1, "model", "gpt-4");
        var raw = new RawMessage(msg.toJson());
        var result = filter.filter("b1", raw, raw);

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertSame(raw, result.message());
    }

    @Test
    public void testZeroCountForUnknownSession() {
        assertEquals(0, filter.getSessionTokenCount("unknown"));
        assertEquals(0, filter.getBroadcasterTokenCount("unknown"));
    }
}
