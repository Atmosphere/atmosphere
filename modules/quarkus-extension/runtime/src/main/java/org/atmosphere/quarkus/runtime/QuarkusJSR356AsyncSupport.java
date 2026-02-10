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
package org.atmosphere.quarkus.runtime;

import org.atmosphere.container.Servlet30CometSupport;
import org.atmosphere.cpr.AtmosphereConfig;

/**
 * JSR-356 WebSocket async support for Quarkus. Extends {@link Servlet30CometSupport}
 * and reports WebSocket as supported, but does NOT register WebSocket endpoints in the
 * constructor (unlike {@code JSR356AsyncSupport}).
 * <p>
 * Endpoint registration is handled by {@link AtmosphereWebSocketExtension} during
 * Undertow deployment setup, before the WebSocket container is locked. This class
 * is specified via the {@code org.atmosphere.cpr.asyncSupport} init param so that
 * Atmosphere knows WebSocket is available without triggering the UT003017 error.
 */
public class QuarkusJSR356AsyncSupport extends Servlet30CometSupport {

    public QuarkusJSR356AsyncSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

    @Override
    public String getContainerName() {
        return super.getContainerName() + " and jsr356/WebSocket API (Quarkus)";
    }
}
