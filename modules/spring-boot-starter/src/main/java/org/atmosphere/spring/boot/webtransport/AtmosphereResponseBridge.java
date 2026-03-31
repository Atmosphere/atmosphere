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
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import reactor.core.publisher.Sinks;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Creates an {@link AtmosphereResponse} that bridges writes back to a
 * Reactor Netty {@link HttpServerResponse}. For regular HTTP/3 requests,
 * the response body is collected and flushed via a Reactor {@link Sinks.One}.
 *
 * <p>For WebTransport sessions, the response is associated with a
 * {@link ReactorNettyWebTransportSession} instead, and writes go directly
 * to the QUIC bidirectional stream.</p>
 */
public final class AtmosphereResponseBridge {

    private AtmosphereResponseBridge() {
    }

    /**
     * Create an AtmosphereResponse for a regular HTTP/3 request.
     * The response will NOT delegate to a native servlet response
     * (there isn't one). Writes accumulate in the AsyncIOWriter and
     * the caller flushes them to the Reactor Netty response.
     *
     * @param request the associated AtmosphereRequest
     * @return an AtmosphereResponse
     */
    public static AtmosphereResponse wrap(AtmosphereRequest request) {
        var response = new AtmosphereResponseImpl.Builder()
                .request(request)
                .status(200)
                .statusMessage("OK")
                .writeHeader(false)
                .destroyable(false)
                .build();
        response.delegateToNativeResponse(false);
        return response;
    }
}
