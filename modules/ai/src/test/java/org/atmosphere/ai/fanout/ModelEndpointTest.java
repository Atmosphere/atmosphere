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
package org.atmosphere.ai.fanout;

import org.atmosphere.ai.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelEndpointTest {

    @Test
    void creationWithAllFields() {
        var client = Mockito.mock(LlmClient.class);
        var endpoint = new ModelEndpoint("gemini", client, "gemini-pro");

        assertEquals("gemini", endpoint.id());
        assertEquals(client, endpoint.client());
        assertEquals("gemini-pro", endpoint.model());
    }

    @Test
    void nullFieldsAreAllowed() {
        var endpoint = new ModelEndpoint(null, null, null);

        assertNull(endpoint.id());
        assertNull(endpoint.client());
        assertNull(endpoint.model());
    }

    @Test
    void recordEquality() {
        var client = Mockito.mock(LlmClient.class);
        var a = new ModelEndpoint("id1", client, "model1");
        var b = new ModelEndpoint("id1", client, "model1");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
