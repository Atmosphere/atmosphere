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
package org.atmosphere.spring.boot.webtransport;

import org.atmosphere.spring.boot.AtmosphereProperties;
import org.atmosphere.spring.boot.AtmosphereWebTransportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoint exposing the WebTransport server configuration for the
 * browser client. Returns the HTTP/3 port and certificate hash needed
 * for {@code serverCertificateHashes} (self-signed dev certs).
 *
 * <p>Uses {@code @AutoConfiguration} + {@code @RestController} (same pattern
 * as {@code AtmosphereConsoleInfoEndpoint}) so the conditions gate creation
 * without needing a separate {@code @Bean} method.</p>
 */
@AutoConfiguration(after = AtmosphereWebTransportAutoConfiguration.class)
@RestController
@ConditionalOnBean(ReactorNettyTransportServer.class)
@EnableConfigurationProperties(AtmosphereProperties.class)
public class WebTransportInfoController {

    private final ReactorNettyTransportServer server;
    private final AtmosphereProperties properties;

    public WebTransportInfoController(ReactorNettyTransportServer server, AtmosphereProperties properties) {
        this.server = server;
        this.properties = properties;
    }

    @GetMapping("/api/webtransport-info")
    public Map<String, Object> info() {
        var result = new LinkedHashMap<String, Object>();
        result.put("port", properties.getWebTransport().getPort());
        result.put("enabled", server.isRunning());
        var hash = server.certificateHash();
        if (hash != null) {
            result.put("certificateHash", hash);
        }
        return result;
    }
}
