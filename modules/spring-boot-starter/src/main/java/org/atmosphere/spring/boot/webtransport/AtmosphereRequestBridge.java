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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.util.FakeHttpSession;
import reactor.netty.http.server.HttpServerRequest;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Converts a Reactor Netty {@link HttpServerRequest} into an
 * {@link AtmosphereRequest}. This is the Nettosphere bridge pattern:
 * fake a servlet request from a Netty connection so the Atmosphere
 * framework can process it via {@code doCometSupport()}.
 */
public final class AtmosphereRequestBridge {

    private AtmosphereRequestBridge() {
    }

    /**
     * Wrap a Reactor Netty request into an AtmosphereRequest.
     *
     * @param nettyRequest the incoming HTTP/3 request
     * @param servletPath  the configured Atmosphere servlet path (e.g. "")
     * @return a fully populated AtmosphereRequest
     */
    public static AtmosphereRequest wrap(HttpServerRequest nettyRequest, String servletPath) {
        var uri = nettyRequest.uri();
        var queryStart = uri.indexOf('?');
        var path = queryStart >= 0 ? uri.substring(0, queryStart) : uri;
        var queryString = queryStart >= 0 ? uri.substring(queryStart + 1) : "";

        var headers = extractHeaders(nettyRequest);

        var remoteAddr = "";
        var remotePort = 0;
        var remoteAddress = nettyRequest.remoteAddress();
        if (remoteAddress instanceof InetSocketAddress inet) {
            remoteAddr = inet.getAddress().getHostAddress();
            remotePort = inet.getPort();
        }

        var serverName = "";
        var serverPort = 0;
        var hostAddress = nettyRequest.hostAddress();
        if (hostAddress instanceof InetSocketAddress inet) {
            serverName = inet.getHostString();
            serverPort = inet.getPort();
        }

        var session = new FakeHttpSession(
                UUID.randomUUID().toString(), null,
                System.currentTimeMillis(), -1);

        return new AtmosphereRequestImpl.Builder()
                .requestURI(path)
                .pathInfo(path)
                .servletPath(servletPath)
                .contextPath("")
                .method(nettyRequest.method().name())
                .headers(headers)
                .queryString(queryString)
                .remoteAddr(remoteAddr)
                .remotePort(remotePort)
                .serverName(serverName)
                .serverPort(serverPort)
                .isSecure(true) // HTTP/3 always uses TLS
                .session(session)
                .contentType(headers.getOrDefault("Content-Type", "text/plain"))
                .build();
    }

    /**
     * Create a minimal AtmosphereRequest for a WebTransport session.
     *
     * @param nettyRequest the initial CONNECT request
     * @param transport    the transport name ("webtransport")
     * @return an AtmosphereRequest with WebTransport headers
     */
    public static AtmosphereRequest wrapForWebTransport(HttpServerRequest nettyRequest, String transport) {
        var request = wrap(nettyRequest, "");
        request.header("X-Atmosphere-Transport", transport);
        return request;
    }

    private static Map<String, String> extractHeaders(HttpServerRequest nettyRequest) {
        var headers = new HashMap<String, String>();
        for (var entry : nettyRequest.requestHeaders()) {
            headers.put(entry.getKey(), entry.getValue());
        }
        return headers;
    }
}
