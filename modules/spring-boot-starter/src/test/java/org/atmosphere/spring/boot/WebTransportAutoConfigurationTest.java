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
package org.atmosphere.spring.boot;

import org.atmosphere.spring.boot.AtmosphereProperties.WebTransportProperties;
import org.atmosphere.spring.boot.webtransport.AltSvcFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebTransportAutoConfigurationTest {

    // ── WebTransportProperties defaults ──────────────────────────────────

    @Test
    void webTransportPropertiesDefaults() {
        var props = new WebTransportProperties();
        assertFalse(props.isEnabled(), "WebTransport should be disabled by default");
        assertEquals("0.0.0.0", props.getHost(), "Default host should be 0.0.0.0");
        assertEquals(4443, props.getPort(), "Default port should be 4443");
        assertTrue(props.isAddAltSvc(), "Alt-Svc header should be enabled by default");
    }

    @Test
    void defaultPortIs4443() {
        var props = new WebTransportProperties();
        assertEquals(4443, props.getPort());
    }

    @Test
    void customPortFromProperties() {
        var props = new WebTransportProperties();
        props.setPort(8443);
        assertEquals(8443, props.getPort());
    }

    @Test
    void enabledPropertyRoundTrip() {
        var props = new WebTransportProperties();
        assertFalse(props.isEnabled());
        props.setEnabled(true);
        assertTrue(props.isEnabled());
    }

    @Test
    void hostPropertyRoundTrip() {
        var props = new WebTransportProperties();
        assertEquals("0.0.0.0", props.getHost());
        props.setHost("127.0.0.1");
        assertEquals("127.0.0.1", props.getHost());
    }

    @Test
    void addAltSvcPropertyRoundTrip() {
        var props = new WebTransportProperties();
        assertTrue(props.isAddAltSvc());
        props.setAddAltSvc(false);
        assertFalse(props.isAddAltSvc());
    }

    @Test
    void sslPropertiesDefaults() {
        var props = new WebTransportProperties();
        assertNotNull(props.getSsl());
        assertNull(props.getSsl().getCertificate());
        assertNull(props.getSsl().getPrivateKey());
        assertNull(props.getSsl().getPrivateKeyPassword());
    }

    @Test
    void sslPropertiesRoundTrip() {
        var ssl = new WebTransportProperties.SslProperties();
        ssl.setCertificate("/path/to/cert.pem");
        ssl.setPrivateKey("/path/to/key.pem");
        ssl.setPrivateKeyPassword("secret");
        assertEquals("/path/to/cert.pem", ssl.getCertificate());
        assertEquals("/path/to/key.pem", ssl.getPrivateKey());
        assertEquals("secret", ssl.getPrivateKeyPassword());
    }

    @Test
    void sslPropertySetterOnParent() {
        var props = new WebTransportProperties();
        var ssl = new WebTransportProperties.SslProperties();
        ssl.setCertificate("cert.pem");
        props.setSsl(ssl);
        assertEquals("cert.pem", props.getSsl().getCertificate());
    }

    // ── AtmosphereProperties integration ─────────────────────────────────

    @Test
    void atmospherePropertiesWebTransportAccessor() {
        var props = new AtmosphereProperties();
        assertNotNull(props.getWebTransport());
        assertFalse(props.getWebTransport().isEnabled());
    }

    @Test
    void atmospherePropertiesWebTransportSetter() {
        var props = new AtmosphereProperties();
        var wt = new WebTransportProperties();
        wt.setEnabled(true);
        wt.setPort(9443);
        props.setWebTransport(wt);
        assertTrue(props.getWebTransport().isEnabled());
        assertEquals(9443, props.getWebTransport().getPort());
    }

    // ── AltSvcFilter direct tests ────────────────────────────────────────

    @Test
    void altSvcFilterAddsHeader() throws Exception {
        var filter = new AltSvcFilter(4443);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertEquals("h3=\":4443\"; ma=86400", response.getHeader("Alt-Svc"));
    }

    @Test
    void altSvcFilterCustomPort() throws Exception {
        var filter = new AltSvcFilter(8443);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertEquals("h3=\":8443\"; ma=86400", response.getHeader("Alt-Svc"));
    }

    @Test
    void altSvcFilterChainsToNextFilter() throws Exception {
        var filter = new AltSvcFilter(4443);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var chainCalled = new boolean[]{false};
        filter.doFilter(request, response, (req, res) -> chainCalled[0] = true);
        assertTrue(chainCalled[0], "Filter chain should be invoked");
    }

    @Test
    void altSvcFilterHeaderPresentAfterChain() throws Exception {
        var filter = new AltSvcFilter(4443);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            // Verify header is set before chain executes downstream
            assertNotNull(((MockHttpServletResponse) res).getHeader("Alt-Svc"));
        });
    }

    @Test
    void altSvcFilterWithPort443() throws Exception {
        var filter = new AltSvcFilter(443);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertEquals("h3=\":443\"; ma=86400", response.getHeader("Alt-Svc"));
    }
}
