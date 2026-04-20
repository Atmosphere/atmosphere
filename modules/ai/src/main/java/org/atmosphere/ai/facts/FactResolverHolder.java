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
package org.atmosphere.ai.facts;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide accessor for the live {@link FactResolver}. Endpoint
 * handlers and context providers reach a single resolver instance through
 * this holder; tests install a per-test resolver via
 * {@link #install(FactResolver)} and restore the default in teardown.
 *
 * <p>Same shape as {@code AiGatewayHolder} / {@code RunRegistryHolder} —
 * the holder pattern is the wire that turns the SPI from "published API"
 * into a real primitive.</p>
 */
public final class FactResolverHolder {

    private static final AtomicReference<FactResolver> HOLDER =
            new AtomicReference<>(new DefaultFactResolver());

    private FactResolverHolder() {
        // static holder
    }

    /** Install the process-wide resolver. */
    public static void install(FactResolver resolver) {
        HOLDER.set(Objects.requireNonNull(resolver, "resolver"));
    }

    /** Restore the default resolver. Primarily for tests. */
    public static void reset() {
        HOLDER.set(new DefaultFactResolver());
    }

    /** Fetch the current resolver. Never {@code null}. */
    public static FactResolver get() {
        return HOLDER.get();
    }
}
