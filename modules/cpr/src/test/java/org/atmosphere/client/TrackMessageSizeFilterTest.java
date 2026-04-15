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
package org.atmosphere.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.HeaderConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrackMessageSizeFilterTest {

    private TrackMessageSizeFilter filter;
    private AtmosphereResource resource;
    private AtmosphereRequest request;

    @BeforeEach
    void setUp() {
        filter = new TrackMessageSizeFilter();
        resource = mock(AtmosphereResource.class);
        request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
    }

    @Test
    void perRequestFilterPrependsLengthWhenHeaderPresent() {
        when(resource.uuid()).thenReturn("some-uuid");
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE)).thenReturn("true");

        BroadcastAction result = filter.filter("b1", resource, "hello", "hello");

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertEquals("5|hello", result.message());
    }

    @Test
    void perRequestFilterPrependsLengthForVoidUuid() {
        when(resource.uuid()).thenReturn(BroadcastFilter.VOID_ATMOSPHERE_RESOURCE_UUID);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE)).thenReturn(null);

        BroadcastAction result = filter.filter("b1", resource, "helloworld", "helloworld");

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertEquals("10|helloworld", result.message());
    }

    @Test
    void perRequestFilterPassesThroughWithoutHeader() {
        when(resource.uuid()).thenReturn("some-uuid");
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE)).thenReturn(null);

        BroadcastAction result = filter.filter("b1", resource, "hello", "hello");

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertEquals("hello", result.message());
    }

    @Test
    void perRequestFilterPassesThroughNonStringMessages() {
        when(resource.uuid()).thenReturn(BroadcastFilter.VOID_ATMOSPHERE_RESOURCE_UUID);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE)).thenReturn("true");

        Integer msg = 42;
        BroadcastAction result = filter.filter("b1", resource, msg, msg);

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertEquals(42, result.message());
    }

    @Test
    void perRequestFilterTrimsWhitespace() {
        when(resource.uuid()).thenReturn(BroadcastFilter.VOID_ATMOSPHERE_RESOURCE_UUID);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE)).thenReturn(null);

        BroadcastAction result = filter.filter("b1", resource, "  hi  ", "  hi  ");

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertEquals("2|hi", result.message());
    }

    @Test
    void perRequestFilterHandlesEmptyString() {
        when(resource.uuid()).thenReturn(BroadcastFilter.VOID_ATMOSPHERE_RESOURCE_UUID);

        BroadcastAction result = filter.filter("b1", resource, "", "");

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertEquals("0|", result.message());
    }

    @Test
    void perRequestFilterHeaderCaseInsensitive() {
        when(resource.uuid()).thenReturn("some-uuid");
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE)).thenReturn("TRUE");

        BroadcastAction result = filter.filter("b1", resource, "abc", "abc");

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertEquals("3|abc", result.message());
    }

    @Test
    void broadcastFilterReturnsMessageUnchanged() {
        BroadcastAction result = filter.filter("b1", "original", "hello");

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertEquals("hello", result.message());
    }
}
