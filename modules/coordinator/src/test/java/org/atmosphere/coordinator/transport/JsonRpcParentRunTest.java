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
package org.atmosphere.coordinator.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * The send side of parent-run propagation lands the coordinator's tape run id in
 * the A2A message's {@code metadata} (alongside the fixed {@code skillId}), so
 * the receiving task inherits it. Asserts the wire shape directly.
 */
class JsonRpcParentRunTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void dispatchMetadataMergesIntoMessageMetadata() throws Exception {
        var body = JsonRpcUtils.buildSendRequest("assess",
                Map.of("query", "market"),
                Map.of("atmosphere.tape.parentRunId", "ceo-run-1"));

        var metadata = MAPPER.readTree(body).get("params").get("message").get("metadata");
        assertEquals("assess", metadata.get("skillId").stringValue(),
                "the skill id stays on the message");
        assertEquals("ceo-run-1", metadata.get("atmosphere.tape.parentRunId").stringValue(),
                "the coordinator run id must ride the message metadata");
    }

    @Test
    void noDispatchMetadataLeavesOnlySkillId() throws Exception {
        var body = JsonRpcUtils.buildSendRequest("assess", Map.of("query", "market"));

        var metadata = MAPPER.readTree(body).get("params").get("message").get("metadata");
        assertEquals("assess", metadata.get("skillId").stringValue());
        assertFalse(metadata.has("atmosphere.tape.parentRunId"),
                "no parent-run key when none was supplied");
    }
}
