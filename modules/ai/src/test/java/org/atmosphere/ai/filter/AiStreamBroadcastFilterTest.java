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

public class AiStreamBroadcastFilterTest {

    /** A simple pass-through filter for testing the base class mechanics. */
    private static class PassThroughFilter extends AiStreamBroadcastFilter {
        AiStreamMessage lastMessage;

        @Override
        protected BroadcastAction filterAiMessage(
                String broadcasterId, AiStreamMessage msg, String originalJson, RawMessage rawMessage) {
            lastMessage = msg;
            return new BroadcastAction(rawMessage);
        }
    }

    /** A filter that uppercases token data. */
    private static class UpperCaseFilter extends AiStreamBroadcastFilter {
        @Override
        protected BroadcastAction filterAiMessage(
                String broadcasterId, AiStreamMessage msg, String originalJson, RawMessage rawMessage) {
            if (msg.isToken() && msg.data() != null) {
                var modified = msg.withData(msg.data().toUpperCase());
                return new BroadcastAction(new RawMessage(modified.toJson()));
            }
            return new BroadcastAction(rawMessage);
        }
    }

    @Test
    public void testPassesThroughNonRawMessage() {
        var filter = new PassThroughFilter();
        var result = filter.filter("b1", "plain string", "plain string");

        assertEquals("plain string", result.message());
        assertNull(filter.lastMessage);
    }

    @Test
    public void testPassesThroughRawMessageWithNonStringContent() {
        var filter = new PassThroughFilter();
        var raw = new RawMessage(42);
        var result = filter.filter("b1", raw, raw);

        assertSame(raw, result.message());
        assertNull(filter.lastMessage);
    }

    @Test
    public void testPassesThroughNonAiJson() {
        var filter = new PassThroughFilter();
        var raw = new RawMessage("{\"foo\":\"bar\"}");
        var result = filter.filter("b1", raw, raw);

        assertSame(raw, result.message());
        assertNull(filter.lastMessage);
    }

    @Test
    public void testParsesAiTokenMessage() {
        var filter = new PassThroughFilter();
        var json = "{\"type\":\"token\",\"data\":\"Hello\",\"sessionId\":\"s1\",\"seq\":1}";
        var raw = new RawMessage(json);
        var result = filter.filter("b1", raw, raw);

        assertNotNull(filter.lastMessage);
        assertTrue(filter.lastMessage.isToken());
        assertEquals("Hello", filter.lastMessage.data());
        assertEquals("s1", filter.lastMessage.sessionId());
    }

    @Test
    public void testParsesAiCompleteMessage() {
        var filter = new PassThroughFilter();
        var json = "{\"type\":\"complete\",\"sessionId\":\"s1\",\"seq\":5}";
        var raw = new RawMessage(json);
        filter.filter("b1", raw, raw);

        assertNotNull(filter.lastMessage);
        assertTrue(filter.lastMessage.isComplete());
        assertNull(filter.lastMessage.data());
    }

    @Test
    public void testUpperCaseFilterTransformsTokenData() throws Exception {
        var filter = new UpperCaseFilter();
        var json = "{\"type\":\"token\",\"data\":\"hello world\",\"sessionId\":\"s1\",\"seq\":1}";
        var raw = new RawMessage(json);
        var result = filter.filter("b1", raw, raw);

        var resultRaw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) resultRaw.message());
        assertEquals("HELLO WORLD", parsed.data());
        assertEquals("s1", parsed.sessionId());
    }

    @Test
    public void testUpperCaseFilterPassesThroughNonTokenMessages() {
        var filter = new UpperCaseFilter();
        var json = "{\"type\":\"complete\",\"sessionId\":\"s1\",\"seq\":5}";
        var raw = new RawMessage(json);
        var result = filter.filter("b1", raw, raw);

        assertSame(raw, result.message());
    }

    @Test
    public void testPassesThroughMalformedJson() {
        var filter = new PassThroughFilter();
        var raw = new RawMessage("not valid json {{{");
        var result = filter.filter("b1", raw, raw);

        assertSame(raw, result.message());
        assertNull(filter.lastMessage);
    }

    @Test
    public void testBroadcastActionContinueByDefault() {
        var filter = new PassThroughFilter();
        var json = "{\"type\":\"token\",\"data\":\"test\",\"sessionId\":\"s1\",\"seq\":1}";
        var raw = new RawMessage(json);
        var result = filter.filter("b1", raw, raw);

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
    }
}
