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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationConfigTest {

    @Test
    void atmosphereXmlPropertyValue() {
        assertEquals("org.atmosphere.atmosphereDotXml", ApplicationConfig.PROPERTY_ATMOSPHERE_XML);
    }

    @Test
    void blockingCometSupportPropertyValue() {
        assertEquals("org.atmosphere.useBlocking", ApplicationConfig.PROPERTY_BLOCKING_COMETSUPPORT);
    }

    @Test
    void webSocketSupportPropertyValue() {
        assertEquals("org.atmosphere.useWebSocket", ApplicationConfig.WEBSOCKET_SUPPORT);
    }

    @Test
    void broadcasterCachePropertyValue() {
        assertEquals("org.atmosphere.cpr.broadcasterCacheClass", ApplicationConfig.BROADCASTER_CACHE);
    }

    @Test
    void heartbeatIntervalPropertyValue() {
        assertEquals("org.atmosphere.interceptor.HeartbeatInterceptor.heartbeatFrequencyInSeconds",
                ApplicationConfig.HEARTBEAT_INTERVAL_IN_SECONDS);
    }

    @Test
    void suspendedResourceUuidPropertyValue() {
        assertEquals("org.atmosphere.cpr.AtmosphereResource.suspended.uuid",
                ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
    }

    @Test
    void maxInactivePropertyValue() {
        assertEquals("org.atmosphere.cpr.CometSupport.maxInactiveActivity",
                ApplicationConfig.MAX_INACTIVE);
    }

    @Test
    void broadcasterLifecyclePolicyPropertyValue() {
        assertEquals("org.atmosphere.cpr.broadcasterLifeCyclePolicy",
                ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY);
    }

    @Test
    void broadcasterClassPropertyValue() {
        assertEquals("org.atmosphere.cpr.broadcasterClass", ApplicationConfig.BROADCASTER_CLASS);
    }

    @Test
    void recycleRequestResponsePropertyValue() {
        assertEquals("org.atmosphere.cpr.recycleAtmosphereRequestResponse",
                ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
    }

    @Test
    void webSocketProtocolClassPropertyValue() {
        assertEquals("org.atmosphere.websocket.WebSocketProtocol",
                ApplicationConfig.WEBSOCKET_PROTOCOL);
    }

    @Test
    void propertyServletMappingValue() {
        assertEquals("org.atmosphere.jersey.servlet-mapping",
                ApplicationConfig.PROPERTY_SERVLET_MAPPING);
    }

    @Test
    void nativeCometSupportPropertyValue() {
        assertEquals("org.atmosphere.useNative", ApplicationConfig.PROPERTY_NATIVE_COMETSUPPORT);
    }

    @Test
    void useStreamPropertyValue() {
        assertEquals("org.atmosphere.useStream", ApplicationConfig.PROPERTY_USE_STREAM);
    }

    @Test
    void broadcasterFactoryPropertyStartsWithOrgAtmosphere() {
        assertNotNull(ApplicationConfig.BROADCASTER_FACTORY);
        assertTrue(ApplicationConfig.BROADCASTER_FACTORY.startsWith("org.atmosphere.cpr."));
    }

    @Test
    void websocketServlet3SupportPropertyValue() {
        assertEquals("org.atmosphere.useWebSocketAndServlet3",
                ApplicationConfig.WEBSOCKET_SUPPORT_SERVLET3);
    }

    @Test
    void throwExceptionOnClonedRequestPropertyValue() {
        assertEquals("org.atmosphere.throwExceptionOnClonedRequest",
                ApplicationConfig.PROPERTY_THROW_EXCEPTION_ON_CLONED_REQUEST);
    }
}
