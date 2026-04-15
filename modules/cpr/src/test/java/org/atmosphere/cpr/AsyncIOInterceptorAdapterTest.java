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
package org.atmosphere.cpr;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncIOInterceptorAdapterTest {

    private final AsyncIOInterceptorAdapter adapter =
            new AsyncIOInterceptorAdapter();
    private final AtmosphereResponse response =
            Mockito.mock(AtmosphereResponse.class);

    @Test
    void prePayloadDoesNotThrow() {
        assertDoesNotThrow(() ->
                adapter.prePayload(response, new byte[]{1}, 0, 1));
    }

    @Test
    void postPayloadDoesNotThrow() {
        assertDoesNotThrow(() ->
                adapter.postPayload(response, new byte[]{1}, 0, 1));
    }

    @Test
    void transformPayloadReturnsDraft() throws IOException {
        byte[] draft = {1, 2, 3};
        byte[] data = {4, 5};
        byte[] result = adapter.transformPayload(response, draft, data);
        assertSame(draft, result);
    }

    @Test
    void errorReturnsFormattedMessage() {
        byte[] result = adapter.error(response, 500, "Internal Error");
        assertEquals("ERROR: 500:Internal Error", new String(result));
    }

    @Test
    void errorWith404() {
        byte[] result = adapter.error(response, 404, "Not Found");
        assertTrue(new String(result).contains("404"));
        assertTrue(new String(result).contains("Not Found"));
    }

    @Test
    void redirectDoesNotThrow() {
        assertDoesNotThrow(() ->
                adapter.redirect(response, "http://example.com"));
    }
}
