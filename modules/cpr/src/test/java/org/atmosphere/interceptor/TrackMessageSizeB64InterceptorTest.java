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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackMessageSizeB64InterceptorTest {

    private TrackMessageSizeB64Interceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TrackMessageSizeB64Interceptor();
    }

    @Test
    void excludedContentTypeAddsType() {
        var result = interceptor.excludedContentType("application/json");
        // Fluent API returns same instance
        assertTrue(result instanceof TrackMessageSizeB64Interceptor);
    }

    @Test
    void configureReadsExcludedContentTypes() {
        var config = mock(AtmosphereConfig.class);
        var framework = mock(AtmosphereFramework.class);
        when(config.getInitParameter(ApplicationConfig.EXCLUDED_CONTENT_TYPES))
                .thenReturn("text/html,application/json");
        when(config.framework()).thenReturn(framework);
        when(framework.findInterceptor(HeartbeatInterceptor.class))
                .thenReturn(Optional.empty());

        interceptor.configure(config);
        // No exception = success
    }

    @Test
    void configureWithNullExcludedContentTypes() {
        var config = mock(AtmosphereConfig.class);
        var framework = mock(AtmosphereFramework.class);
        when(config.getInitParameter(ApplicationConfig.EXCLUDED_CONTENT_TYPES))
                .thenReturn(null);
        when(config.framework()).thenReturn(framework);
        when(framework.findInterceptor(HeartbeatInterceptor.class))
                .thenReturn(Optional.empty());

        interceptor.configure(config);
    }

    @Test
    void isMessageAlreadyEncodedWithNoHeartbeat() {
        // No configure called → heartbeatInterceptor is null
        assertFalse(interceptor.isMessageAlreadyEncoded("any message"));
    }

    @Test
    void isMessageAlreadyEncodedWithHeartbeat() {
        var heartbeat = mock(HeartbeatInterceptor.class);
        when(heartbeat.getPaddingBytes()).thenReturn("X".getBytes());

        var config = mock(AtmosphereConfig.class);
        var framework = mock(AtmosphereFramework.class);
        when(config.getInitParameter(ApplicationConfig.EXCLUDED_CONTENT_TYPES))
                .thenReturn(null);
        when(config.framework()).thenReturn(framework);
        when(framework.findInterceptor(HeartbeatInterceptor.class))
                .thenReturn(Optional.of(heartbeat));

        interceptor.configure(config);
        assertTrue(interceptor.isMessageAlreadyEncoded("something|X"));
        assertFalse(interceptor.isMessageAlreadyEncoded("something else"));
    }

    @Test
    void toStringContainsDelimiter() {
        assertTrue(interceptor.toString().contains("|"));
    }

    @Test
    void skipInterceptorConstant() {
        assertTrue(TrackMessageSizeB64Interceptor.SKIP_INTERCEPTOR
                .contains("TrackMessageSizeB64Interceptor"));
    }
}
