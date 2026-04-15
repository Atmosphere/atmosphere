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
package org.atmosphere.a2a.types;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fullConstruction() {
        var parts = List.<Part>of(new Part.TextPart("hello"));
        var meta = Map.<String, Object>of("key", "val");
        var msg = new Message("user", parts, "msg-1", "task-1", "ctx-1", meta);
        assertEquals("user", msg.role());
        assertEquals(1, msg.parts().size());
        assertEquals("msg-1", msg.messageId());
        assertEquals("task-1", msg.taskId());
        assertEquals("ctx-1", msg.contextId());
        assertEquals("val", msg.metadata().get("key"));
    }

    @Test
    void nullPartsDefaultsToEmpty() {
        var msg = new Message("agent", null, "m1", null, null, null);
        assertNotNull(msg.parts());
        assertTrue(msg.parts().isEmpty());
    }

    @Test
    void nullMetadataDefaultsToEmpty() {
        var msg = new Message("user", List.of(), "m1", null, null, null);
        assertNotNull(msg.metadata());
        assertTrue(msg.metadata().isEmpty());
    }

    @Test
    void partsListIsUnmodifiable() {
        var msg = new Message("user", List.of(new Part.TextPart("hi")), "m1", null, null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> msg.parts().add(new Part.TextPart("extra")));
    }

    @Test
    void userFactoryCreatesUserRole() {
        var msg = Message.user("Hello agent");
        assertEquals("user", msg.role());
        assertEquals(1, msg.parts().size());
        var textPart = (Part.TextPart) msg.parts().getFirst();
        assertEquals("Hello agent", textPart.text());
    }

    @Test
    void agentFactoryCreatesAgentRole() {
        var msg = Message.agent("Hello user");
        assertEquals("agent", msg.role());
        assertEquals(1, msg.parts().size());
        var textPart = (Part.TextPart) msg.parts().getFirst();
        assertEquals("Hello user", textPart.text());
    }

    @Test
    void userFactoryGeneratesNonNullMessageId() {
        var msg = Message.user("test");
        assertNotNull(msg.messageId());
        assertEquals(36, msg.messageId().length());
    }

    @Test
    void agentFactoryGeneratesUniqueIds() {
        var msg1 = Message.agent("a");
        var msg2 = Message.agent("b");
        assertNotNull(msg1.messageId());
        assertNotNull(msg2.messageId());
        assertTrue(!msg1.messageId().equals(msg2.messageId()),
                "Each message should have a unique ID");
    }

    @Test
    void jsonRoundTrip() throws Exception {
        var msg = new Message("user", List.of(new Part.TextPart("test")),
                "msg-42", "task-1", "ctx-1", Map.of("meta", "data"));
        String json = mapper.writeValueAsString(msg);
        var deserialized = mapper.readValue(json, Message.class);
        assertEquals(msg.role(), deserialized.role());
        assertEquals(msg.messageId(), deserialized.messageId());
        assertEquals(msg.taskId(), deserialized.taskId());
        assertEquals(msg.contextId(), deserialized.contextId());
        assertEquals(1, deserialized.parts().size());
    }
}
