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
package org.atmosphere.wasync.impl;

import org.atmosphere.wasync.Client;
import org.atmosphere.wasync.Options;
import org.atmosphere.wasync.OptionsBuilder;
import org.atmosphere.wasync.RequestBuilder;
import org.atmosphere.wasync.Socket;

/**
 * A {@link Client} for connecting to Atmosphere servers. Creates
 * {@link AtmosphereRequestBuilder} instances that include Atmosphere
 * protocol handshake parameters.
 *
 * <pre>{@code
 * AtmosphereClient client = AtmosphereClient.create();
 * Request request = client.newRequestBuilder()
 *     .uri("ws://localhost:8080/chat")
 *     .transport(Request.TRANSPORT.WEBSOCKET)
 *     .enableProtocol(true)
 *     .build();
 * Socket socket = client.create()
 *     .on(Event.MESSAGE, m -> System.out.println(m))
 *     .open(request);
 * }</pre>
 */
public class AtmosphereClient implements Client {

    /**
     * Create a new AtmosphereClient.
     */
    public static AtmosphereClient newClient() {
        return new AtmosphereClient();
    }

    @Override
    public Socket create() {
        return new AtmosphereSocket((DefaultOptions) newOptionsBuilder().build());
    }

    @Override
    public Socket create(Options options) {
        return new AtmosphereSocket((DefaultOptions) options);
    }

    @Override
    public AtmosphereRequestBuilder newRequestBuilder() {
        return new AtmosphereRequestBuilder();
    }

    @Override
    public DefaultOptionsBuilder newOptionsBuilder() {
        return new DefaultOptionsBuilder();
    }
}
