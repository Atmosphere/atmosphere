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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * The message text of a call with several arguments in an unordered map used to
 * be whichever value its iteration gave first: the startup-team's
 * {@code Map.of("query", ..., "num_results", "3")} call went out as {@code "3"}
 * as easily as its query.
 */
class JsonRpcMessageTextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void severalArgumentsOfAnUnorderedMapAllGoAsSortedJson() throws Exception {
        var args = new HashMap<String, Object>();
        args.put("query", "gardening market");
        args.put("num_results", "3");
        var text = MAPPER.readTree(JsonRpcUtils.buildSendRequest("web_search", Map.copyOf(args)))
                .get("params").get("message").get("parts").get(0).get("text").stringValue();
        assertEquals("{\"num_results\":\"3\",\"query\":\"gardening market\"}", text);
        assertEquals(text, JsonRpcUtils.messageText(args), "a HashMap is unordered too");
    }

    @Test
    void aSingleArgumentOrAnOrderedMapKeepsItsFirstValue() {
        assertEquals("gardening market", JsonRpcUtils.messageText(Map.of("query", "gardening market")));
        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("query", "gardening market");
        ordered.put("num_results", "3");
        assertEquals("gardening market", JsonRpcUtils.messageText(ordered),
                "an ordered map's first argument is the one its caller put first");
        assertEquals("", JsonRpcUtils.messageText(Map.of()));
    }
}
