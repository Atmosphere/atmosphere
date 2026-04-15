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
package org.atmosphere.interceptor;

import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TrackMessageSizeInterceptorTest {

    @Test
    void extendsAtmosphereInterceptorAdapter() {
        var interceptor = new TrackMessageSizeInterceptor();
        assertInstanceOf(AtmosphereInterceptorAdapter.class, interceptor);
    }

    @Test
    void defaultDelimiterInToString() {
        var interceptor = new TrackMessageSizeInterceptor();
        String desc = interceptor.toString();
        assertNotNull(desc);
        org.junit.jupiter.api.Assertions.assertTrue(desc.contains("|"),
                "toString should contain the default delimiter '|'");
    }

    @Test
    void messageDelimiterReturnsSelf() {
        var interceptor = new TrackMessageSizeInterceptor();
        var result = interceptor.messageDelimiter("##");
        assertEquals(interceptor, result, "messageDelimiter should return this for fluent chaining");
    }

    @Test
    void customDelimiterReflectedInToString() {
        var interceptor = new TrackMessageSizeInterceptor();
        interceptor.messageDelimiter("##");
        org.junit.jupiter.api.Assertions.assertTrue(interceptor.toString().contains("##"),
                "toString should contain the custom delimiter");
    }

    @Test
    void excludedContentTypeReturnsSelf() {
        var interceptor = new TrackMessageSizeInterceptor();
        var result = interceptor.excludedContentType("application/json");
        assertEquals(interceptor, result, "excludedContentType should return this for fluent chaining");
    }

    @Test
    void excludedContentTypesAreStoredLowerCase() {
        var interceptor = new TrackMessageSizeInterceptor();
        interceptor.excludedContentType("Application/JSON");
        org.junit.jupiter.api.Assertions.assertTrue(
                interceptor.excludedContentTypes().contains("application/json"),
                "excluded content types should be stored in lower case");
    }

    @Test
    void multipleExcludedContentTypes() {
        var interceptor = new TrackMessageSizeInterceptor();
        interceptor.excludedContentType("text/xml");
        interceptor.excludedContentType("text/html");
        assertEquals(2, interceptor.excludedContentTypes().size());
    }

    @Test
    void priorityIsBeforeDefault() {
        var interceptor = new TrackMessageSizeInterceptor();
        assertEquals(InvokationOrder.BEFORE_DEFAULT, interceptor.priority());
    }

    @Test
    void skipInterceptorConstantIsClassName() {
        String expected = TrackMessageSizeInterceptor.class.getName() + ".skip";
        assertEquals(expected, TrackMessageSizeInterceptor.SKIP_INTERCEPTOR);
    }
}
