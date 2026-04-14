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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AsyncIOAdaptersTest {

    private AsyncIOInterceptorAdapter interceptorAdapter;
    private AsyncIOWriterAdapter writerAdapter;
    private AtmosphereResponse response;

    @BeforeEach
    void setUp() {
        interceptorAdapter = new AsyncIOInterceptorAdapter();
        writerAdapter = new AsyncIOWriterAdapter();
        response = mock(AtmosphereResponse.class);
    }

    // --- AsyncIOInterceptorAdapter ---

    @Test
    void prePayloadDoesNotThrow() {
        assertDoesNotThrow(() ->
                interceptorAdapter.prePayload(response, new byte[]{1}, 0, 1));
    }

    @Test
    void transformPayloadReturnsResponseDraft() throws IOException {
        byte[] draft = {1, 2, 3};
        byte[] data = {4, 5};
        byte[] result = interceptorAdapter.transformPayload(response, draft, data);
        assertSame(draft, result);
    }

    @Test
    void postPayloadDoesNotThrow() {
        assertDoesNotThrow(() ->
                interceptorAdapter.postPayload(response, new byte[]{1}, 0, 1));
    }

    @Test
    void errorReturnsFormattedMessage() {
        byte[] result = interceptorAdapter.error(response, 500, "Internal Server Error");
        assertNotNull(result);
        String msg = new String(result);
        assertTrue(msg.contains("500"));
        assertTrue(msg.contains("Internal Server Error"));
    }

    @Test
    void redirectDoesNotThrow() {
        assertDoesNotThrow(() ->
                interceptorAdapter.redirect(response, "/new-location"));
    }

    // --- AsyncIOWriterAdapter ---

    @Test
    void redirectReturnsSelf() throws IOException {
        assertSame(writerAdapter, writerAdapter.redirect(response, "/loc"));
    }

    @Test
    void writeErrorReturnsSelf() throws IOException {
        assertSame(writerAdapter, writerAdapter.writeError(response, 404, "Not Found"));
    }

    @Test
    void writeStringReturnsSelf() throws IOException {
        assertSame(writerAdapter, writerAdapter.write(response, "data"));
    }

    @Test
    void writeBytesReturnsSelf() throws IOException {
        assertSame(writerAdapter, writerAdapter.write(response, new byte[]{1, 2}));
    }

    @Test
    void writeBytesOffsetReturnsSelf() throws IOException {
        assertSame(writerAdapter, writerAdapter.write(response, new byte[]{1, 2, 3}, 1, 2));
    }

    @Test
    void closeDoesNotThrow() throws IOException {
        assertDoesNotThrow(() -> writerAdapter.close(response));
    }

    @Test
    void flushReturnsSelf() throws IOException {
        assertSame(writerAdapter, writerAdapter.flush(response));
    }
}
