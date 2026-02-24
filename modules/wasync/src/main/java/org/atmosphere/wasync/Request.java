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
package org.atmosphere.wasync;

import java.util.List;
import java.util.Map;

/**
 * Represents an HTTP request to be sent when opening a {@link Socket}.
 *
 * <p>Use a {@link RequestBuilder} to create instances.</p>
 */
public interface Request {

    /**
     * HTTP methods.
     */
    enum METHOD {
        GET, POST, TRACE, PUT, DELETE, OPTIONS
    }

    /**
     * Available transports, in priority order.
     */
    enum TRANSPORT {
        WEBSOCKET, SSE, STREAMING, LONG_POLLING
    }

    /**
     * The list of transports to attempt, in order.
     */
    List<TRANSPORT> transport();

    /**
     * The HTTP method for the request.
     */
    METHOD method();

    /**
     * The HTTP headers.
     */
    Map<String, List<String>> headers();

    /**
     * The query string parameters.
     */
    Map<String, List<String>> queryString();

    /**
     * The encoders to apply when sending data.
     */
    List<Encoder<?, ?>> encoders();

    /**
     * The decoders to apply when receiving data.
     */
    List<Decoder<?, ?>> decoders();

    /**
     * The target URI.
     */
    String uri();

    /**
     * The function resolver for mapping messages to callbacks.
     */
    FunctionResolver functionResolver();
}
