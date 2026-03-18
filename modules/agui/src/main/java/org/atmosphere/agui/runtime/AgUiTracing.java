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
package org.atmosphere.agui.runtime;

import io.opentelemetry.api.OpenTelemetry;
import org.atmosphere.protocol.ProtocolTracing;

/**
 * OpenTelemetry tracing for AG-UI operations. Wraps {@link ProtocolTracing}
 * with AG-UI-specific instrumentation scope and attribute prefix.
 */
public final class AgUiTracing {

    private final ProtocolTracing delegate;

    public AgUiTracing(OpenTelemetry openTelemetry) {
        this.delegate = new ProtocolTracing(openTelemetry, "atmosphere-agui", "4.0.8", "agui");
    }

    /**
     * Returns the underlying {@link ProtocolTracing} instance for tracing AG-UI operations.
     */
    public ProtocolTracing tracing() {
        return delegate;
    }
}
