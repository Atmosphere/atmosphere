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

import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.RequestBuilder;

/**
 * Default {@link RequestBuilder} that produces {@link DefaultRequest} instances.
 */
public class DefaultRequestBuilder extends RequestBuilder<DefaultRequestBuilder> {

    @Override
    public Request build() {
        if (transports.isEmpty()) {
            transport(Request.TRANSPORT.WEBSOCKET);
            transport(Request.TRANSPORT.SSE);
            transport(Request.TRANSPORT.STREAMING);
            transport(Request.TRANSPORT.LONG_POLLING);
        }
        return new DefaultRequest(this);
    }
}
