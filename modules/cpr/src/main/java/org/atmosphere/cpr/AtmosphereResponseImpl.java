/*
 * Copyright 2015 Async-IO.org
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

import javax.servlet.http.HttpServletResponse;

/**
 * An Atmosphere's response representation. An AtmosphereResponse can be used to construct a bi-directional asynchronous
 * application. If the underlying transport is a WebSocket or if its associated {@link AtmosphereResource} has been
 * suspended, this object can be used to write message back to the client at any moment.
 * <br/>
 * This object can delegate the write operation to {@link AsyncIOWriter}.
 */
// added for 2.4.x compatibility
public class AtmosphereResponseImpl extends AtmosphereResponse {

    public AtmosphereResponseImpl(AsyncIOWriter asyncIOWriter, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        super(asyncIOWriter, atmosphereRequest, destroyable);
    }
    public AtmosphereResponseImpl(HttpServletResponse r, AsyncIOWriter asyncIOWriter, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        super(r, asyncIOWriter, atmosphereRequest, destroyable);
    }
}
