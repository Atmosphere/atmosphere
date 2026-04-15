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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtmosphereRequestImplTest {

    @Test
    void newInstanceCreatesEmptyRequest() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        assertNotNull(request);
        assertEquals("", request.getPathInfo());
    }

    @Test
    void wrapPreservesAttributes() {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getAttributeNames()).thenReturn(Collections.enumeration(Collections.singletonList("key1")));
        when(servletRequest.getAttribute("key1")).thenReturn("value1");
        when(servletRequest.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));
        when(servletRequest.getHeaders("content-type")).thenReturn(Collections.enumeration(Collections.emptyList()));

        AtmosphereRequest wrapped = AtmosphereRequestImpl.wrap(servletRequest);
        assertNotNull(wrapped);
        assertEquals("value1", wrapped.getAttribute("key1"));
    }

    @Test
    void wrapDoesNotRewrap() {
        AtmosphereRequest original = AtmosphereRequestImpl.newInstance();
        AtmosphereRequest rewrapped = AtmosphereRequestImpl.wrap(original);
        assertSame(original, rewrapped);
    }

    @Test
    void builderSetsMethod() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.method("POST");
        assertEquals("POST", request.getMethod());
    }

    @Test
    void builderSetsPathInfo() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.pathInfo("/test/path");
        assertEquals("/test/path", request.getPathInfo());
    }

    @Test
    void builderSetsContentType() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.contentType("application/json");
        assertEquals("application/json", request.getContentType());
    }

    @Test
    void headerSetAndGet() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.header("X-Custom", "myvalue");
        assertEquals("myvalue", request.getHeader("X-Custom"));
    }

    @Test
    void headerReturnsNullForMissing() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        assertNull(request.getHeader("NonExistent"));
    }

    @Test
    void headersMapBulkSet() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "text/html");
        headers.put("X-Test", "123");

        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.headers(headers);

        assertEquals("text/html", request.getHeader("Accept"));
        assertEquals("123", request.getHeader("X-Test"));
    }

    @Test
    void headerRemoveWithNull() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.header("X-Remove", "value");
        assertEquals("value", request.getHeader("X-Remove"));

        request.header("X-Remove", null);
        assertNull(request.getHeader("X-Remove"));
    }

    @Test
    void setAttributeAndGetAttribute() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.setAttribute("myattr", "myval");
        assertEquals("myval", request.getAttribute("myattr"));
    }

    @Test
    void setAttributeNullRemovesIt() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.setAttribute("myattr", "myval");
        assertEquals("myval", request.getAttribute("myattr"));

        request.setAttribute("myattr", null);
        assertNull(request.getAttribute("myattr"));
    }

    @Test
    void queryStringSetAndGet() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.queryString("foo=bar&baz=qux");
        assertEquals("foo=bar&baz=qux", request.getQueryString());
    }

    @Test
    void bodyStringSetAndGet() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.body("hello world");
        assertTrue(request.body().hasString());
        assertEquals("hello world", request.body().asString());
    }

    @Test
    void bodyBytesSetAndGet() {
        byte[] data = "bytes".getBytes();
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.body(data);
        assertTrue(request.body().hasBytes());
    }

    @Test
    void servletPathSetAndGet() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.servletPath("/api");
        assertEquals("/api", request.getServletPath());
    }

    @Test
    void requestURISetAndGet() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.requestURI("/app/resource");
        assertEquals("/app/resource", request.getRequestURI());
    }

    @Test
    void destroyableDefaultTrue() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        assertTrue(request.isDestroyable());
    }

    @Test
    void destroyableFlagCanBeChanged() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.destroyable(false);
        assertFalse(request.isDestroyable());
    }

    @Test
    void headersMapReturnsSetHeaders() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.header("H1", "v1");
        request.header("H2", "v2");

        Map<String, String> map = request.headersMap();
        assertEquals("v1", map.get("H1"));
        assertEquals("v2", map.get("H2"));
    }

    @Test
    void contentTypeReturnsViaContentTypeHeader() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.contentType("text/plain");
        assertEquals("text/plain", request.getHeader("content-type"));
    }

    @Test
    void contextPathReturnsSlashByDefault() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.contextPath("/ctx");
        assertEquals("/ctx", request.getContextPath());
    }
}
