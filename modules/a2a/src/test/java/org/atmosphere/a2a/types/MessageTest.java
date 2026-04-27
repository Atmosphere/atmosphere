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

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the v1.0.0 {@link Message} shape (Role enum + collapsed-Part). */
class MessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void userFactoryProducesUserRole() {
        var m = Message.user("hi");
        assertEquals(Role.USER, m.role());
        assertEquals("hi", m.parts().getFirst().text());
    }

    @Test
    void agentFactoryProducesAgentRole() {
        var m = Message.agent("ack");
        assertEquals(Role.AGENT, m.role());
    }

    @Test
    void roleSerializesAsProtoJsonWireForm() throws Exception {
        var json = mapper.writeValueAsString(Message.user("x"));
        assertEquals(true, json.contains("\"role\":\"ROLE_USER\""),
                "expected ROLE_USER, got " + json);
    }

    @Test
    void roleAcceptsLowercaseLegacyForm() throws Exception {
        var m = mapper.readValue(
                "{\"messageId\":\"m1\",\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}",
                Message.class);
        assertEquals(Role.USER, m.role());
    }

    @Test
    void roleAcceptsProtoNameForm() throws Exception {
        var m = mapper.readValue(
                "{\"messageId\":\"m1\",\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"hi\"}]}",
                Message.class);
        assertEquals(Role.AGENT, m.role());
    }

    @Test
    void unknownRoleRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"messageId\":\"m1\",\"role\":\"bystander\",\"parts\":[]}",
                Message.class));
    }

    @Test
    void partsListIsImmutable() {
        var m = Message.user("x");
        assertThrows(UnsupportedOperationException.class,
                () -> m.parts().add(Part.text("y")));
    }
}
