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
package org.atmosphere.ai.resume;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide accessor for the live {@link RunRegistry}. Same pattern as
 * {@code AiGatewayHolder}: endpoint handlers and resume interceptors reach
 * a single registry instance through this holder, while tests install a
 * per-test registry via {@link #install(RunRegistry)} and restore the
 * default in teardown.
 *
 * <p>Existence of this holder is what turns {@link AgentResumeHandle} from
 * a published-but-dead API into a real primitive: the endpoint handler
 * registers every in-flight run here, and reattach code paths consult the
 * same holder to look up a runId received on
 * {@code X-Atmosphere-Run-Id}.</p>
 */
public final class RunRegistryHolder {

    private static final AtomicReference<RunRegistry> HOLDER =
            new AtomicReference<>(new RunRegistry());

    private RunRegistryHolder() {
        // static holder
    }

    /** Install the process-wide registry. */
    public static void install(RunRegistry registry) {
        HOLDER.set(Objects.requireNonNull(registry, "registry"));
    }

    /** Restore the default in-memory registry. Primarily for tests. */
    public static void reset() {
        HOLDER.set(new RunRegistry());
    }

    /** Fetch the current registry. Never {@code null}. */
    public static RunRegistry get() {
        return HOLDER.get();
    }
}
