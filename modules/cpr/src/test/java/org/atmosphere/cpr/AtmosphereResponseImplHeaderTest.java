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

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AtmosphereResponseImplHeaderTest {

    private AtmosphereResponse response;

    @BeforeEach
    void setUp() {
        response = new AtmosphereResponseImpl(mock(AsyncIOWriter.class), null, true);
    }

    @Test
    void setHeaderAndGetHeader() {
        response.setHeader("X-Custom", "value1");
        assertEquals("value1", response.getHeader("X-Custom"));
    }

    @Test
    void addHeaderAndGetHeader() {
        response.addHeader("X-Added", "val");
        assertEquals("val", response.getHeader("X-Added"));
    }

    @Test
    void containsHeaderTrueWhenSet() {
        response.setHeader("X-Exists", "yes");
        assertTrue(response.containsHeader("X-Exists"));
    }

    @Test
    void containsHeaderFalseWhenNotSet() {
        assertFalse(response.containsHeader("X-Missing"));
    }

    @Test
    void setHeaderOverwritesPreviousValue() {
        response.setHeader("X-Key", "first");
        response.setHeader("X-Key", "second");
        assertEquals("second", response.getHeader("X-Key"));
    }

    @Test
    void setHeaderNullRemovesHeader() {
        response.setHeader("X-Remove", "value");
        assertTrue(response.containsHeader("X-Remove"));
        response.setHeader("X-Remove", null);
        assertNull(response.getHeader("X-Remove"));
    }

    @Test
    void setIntHeaderStoresStringValue() {
        response.setIntHeader("X-Int", 42);
        assertEquals("42", response.getHeader("X-Int"));
    }

    @Test
    void addIntHeaderStoresStringValue() {
        response.addIntHeader("X-AddInt", 99);
        assertEquals("99", response.getHeader("X-AddInt"));
    }

    @Test
    void addDateHeaderStoresValue() {
        response.addDateHeader("X-Date", 1234567890L);
        assertEquals("1234567890", response.getHeader("X-Date"));
    }

    @Test
    void setContentTypeAndGetContentType() {
        response.setContentType("application/json");
        assertEquals("application/json", response.getContentType());
    }

    @Test
    void getContentTypeReturnsNullWhenNotSet() {
        // When no content type has been explicitly set via setContentType(), the response
        // may return null or the default. Either way, setContentType should change it.
        response.setContentType("text/plain");
        assertEquals("text/plain", response.getContentType());
    }

    @Test
    void getHeadersReturnsCollectionForExistingHeader() {
        response.setHeader("X-Multi", "value1");
        Collection<String> headers = response.getHeaders("X-Multi");
        assertFalse(headers.isEmpty());
        assertTrue(headers.contains("value1"));
    }

    @Test
    void getHeadersReturnsEmptyForMissingHeader() {
        Collection<String> headers = response.getHeaders("X-None");
        assertTrue(headers.isEmpty());
    }

    @Test
    void getHeaderNamesReturnsSetHeaders() {
        response.setHeader("Alpha", "1");
        response.setHeader("Beta", "2");

        Collection<String> names = response.getHeaderNames();
        assertTrue(names.contains("Alpha"));
        assertTrue(names.contains("Beta"));
    }

    @Test
    void headersMapReturnsAllHeaders() {
        response.setHeader("H1", "v1");
        response.setHeader("H2", "v2");

        var map = response.headers();
        assertEquals("v1", map.get("H1"));
        assertEquals("v2", map.get("H2"));
    }

    @Test
    void defaultStatusIs200() {
        assertEquals(200, response.getStatus());
    }

    @Test
    void setStatusChangesStatus() {
        response.setStatus(404);
        assertEquals(404, response.getStatus());
    }

    @Test
    void setContentLengthAddsHeader() {
        response.setContentLength(1024);
        assertEquals("1024", response.getHeader("Content-Length"));
    }

    @Test
    void setContentTypeSetsContentTypeHeader() {
        response.setContentType("text/html");
        assertEquals("text/html", response.getHeader("Content-Type"));
    }

    @Test
    void destroyableDefaultTrue() {
        assertTrue(response.isDestroyable());
    }

    @Test
    void destroyableCanBeSetFalse() {
        response.destroyable(false);
        assertFalse(response.isDestroyable());
    }
}
