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
package org.atmosphere.a2a.runtime;

import io.opentelemetry.api.OpenTelemetry;
import org.atmosphere.protocol.ProtocolTracing;

/**
 * Wraps an OpenTelemetry instance to provide pre-configured tracing for the A2A protocol.
 * Delegates to {@link ProtocolTracing} with the {@code atmosphere-a2a} instrumentation scope.
 * The instrumentation version is resolved from the package's implementation version when
 * available so it tracks the build automatically.
 */
public final class A2aTracing {

    private static final String INSTRUMENTATION_VERSION = resolveVersion();

    private final ProtocolTracing delegate;

    public A2aTracing(OpenTelemetry openTelemetry) {
        this.delegate = new ProtocolTracing(openTelemetry, "atmosphere-a2a",
                INSTRUMENTATION_VERSION, "a2a");
    }

    public ProtocolTracing tracing() {
        return delegate;
    }

    private static String resolveVersion() {
        var pkg = A2aTracing.class.getPackage();
        var v = pkg != null ? pkg.getImplementationVersion() : null;
        return v != null ? v : "unknown";
    }
}
