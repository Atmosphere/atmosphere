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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AiStreamMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testParseTokenMessage() throws Exception {
        var json = """
                {"type":"token","data":"Hello","sessionId":"abc-123","seq":1}""";
        var msg = AiStreamMessage.parse(json);

        assertNotNull(msg);
        assertTrue(msg.isToken());
        assertFalse(msg.isComplete());
        assertEquals("token", msg.type());
        assertEquals("Hello", msg.data());
        assertEquals("abc-123", msg.sessionId());
        assertEquals(1L, msg.seq());
        assertNull(msg.key());
        assertNull(msg.value());
    }

    @Test
    public void testParseMetadataMessage() throws Exception {
        var json = """
                {"type":"metadata","key":"model","value":"gpt-4","sessionId":"abc-123","seq":3}""";
        var msg = AiStreamMessage.parse(json);

        assertNotNull(msg);
        assertTrue(msg.isMetadata());
        assertEquals("model", msg.key());
        assertEquals("gpt-4", msg.value());
        assertNull(msg.data());
    }

    @Test
    public void testParseCompleteMessageWithoutData() throws Exception {
        var json = """
                {"type":"complete","sessionId":"abc-123","seq":4}""";
        var msg = AiStreamMessage.parse(json);

        assertNotNull(msg);
        assertTrue(msg.isComplete());
        assertNull(msg.data());
    }

    @Test
    public void testParseCompleteMessageWithSummary() throws Exception {
        var json = """
                {"type":"complete","data":"Full response","sessionId":"abc-123","seq":5}""";
        var msg = AiStreamMessage.parse(json);

        assertNotNull(msg);
        assertTrue(msg.isComplete());
        assertEquals("Full response", msg.data());
    }

    @Test
    public void testParseErrorMessage() throws Exception {
        var json = """
                {"type":"error","data":"Connection failed","sessionId":"abc-123","seq":6}""";
        var msg = AiStreamMessage.parse(json);

        assertNotNull(msg);
        assertTrue(msg.isError());
        assertEquals("Connection failed", msg.data());
    }

    @Test
    public void testParseProgressMessage() throws Exception {
        var json = """
                {"type":"progress","data":"Thinking...","sessionId":"abc-123","seq":2}""";
        var msg = AiStreamMessage.parse(json);

        assertNotNull(msg);
        assertTrue(msg.isProgress());
        assertEquals("Thinking...", msg.data());
    }

    @Test
    public void testParseReturnsNullForMissingType() throws Exception {
        var json = """
                {"data":"no type field","sessionId":"abc-123"}""";
        assertNull(AiStreamMessage.parse(json));
    }

    @Test
    public void testParseMetadataWithNumericValue() throws Exception {
        var json = """
                {"type":"metadata","key":"usage.totalTokens","value":42,"sessionId":"abc-123","seq":3}""";
        var msg = AiStreamMessage.parse(json);

        assertNotNull(msg);
        assertEquals(42, msg.value());
    }

    @Test
    public void testParseMetadataWithBooleanValue() throws Exception {
        var json = """
                {"type":"metadata","key":"fanout.complete","value":true,"sessionId":"abc-123","seq":3}""";
        var msg = AiStreamMessage.parse(json);

        assertNotNull(msg);
        assertEquals(true, msg.value());
    }

    @Test
    public void testToJson() throws Exception {
        var msg = new AiStreamMessage("token", "Hello", "abc-123", 1, null, null);
        var json = msg.toJson();
        var node = MAPPER.readTree(json);

        assertEquals("token", node.get("type").asText());
        assertEquals("Hello", node.get("data").asText());
        assertEquals("abc-123", node.get("sessionId").asText());
        assertEquals(1L, node.get("seq").asLong());
        assertFalse(node.has("key"));
        assertFalse(node.has("value"));
    }

    @Test
    public void testToJsonMetadata() throws Exception {
        var msg = new AiStreamMessage("metadata", null, "abc-123", 3, "model", "gpt-4");
        var json = msg.toJson();
        var node = MAPPER.readTree(json);

        assertEquals("metadata", node.get("type").asText());
        assertFalse(node.has("data"));
        assertEquals("model", node.get("key").asText());
        assertEquals("gpt-4", node.get("value").asText());
    }

    @Test
    public void testRoundTrip() throws Exception {
        var original = """
                {"type":"token","data":"Hello world","sessionId":"sess-1","seq":5}""";
        var msg = AiStreamMessage.parse(original);
        var rebuilt = msg.toJson();
        var reparsed = AiStreamMessage.parse(rebuilt);

        assertEquals(msg.type(), reparsed.type());
        assertEquals(msg.data(), reparsed.data());
        assertEquals(msg.sessionId(), reparsed.sessionId());
        assertEquals(msg.seq(), reparsed.seq());
    }

    @Test
    public void testWithData() {
        var msg = new AiStreamMessage("token", "Hello", "abc-123", 1, null, null);
        var modified = msg.withData("REDACTED");

        assertEquals("REDACTED", modified.data());
        assertEquals("token", modified.type());
        assertEquals("abc-123", modified.sessionId());
        assertEquals(1L, modified.seq());
    }
}
