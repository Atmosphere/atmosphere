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
package org.atmosphere.ai.sandbox;

/**
 * The isolation strength a {@link SandboxProvider} offers, ordered weakest to
 * strongest. The taxonomy turns the sandbox into a <em>governance lever</em>:
 * a policy (or a verified plan) can demand a minimum tier, and
 * {@link Sandboxes#select(IsolationTier)} grants the least-privileged backend
 * that still meets the floor — so untrusted code never runs below the isolation
 * it warrants.
 *
 * <ul>
 *   <li>{@link #PROCESS} — runs in a child OS process of the host JVM. Weakest;
 *       suitable only for trusted code or explicit dev opt-in.</li>
 *   <li>{@link #CONTAINER} — OS-level container (namespaces/cgroups), e.g.
 *       Docker. The safe floor for running untrusted code.</li>
 *   <li>{@link #MICRO_VM} — hardware-virtualized micro-VM (Firecracker/gVisor-
 *       class). Stronger kernel isolation than a container.</li>
 *   <li>{@link #REMOTE} — execution off the host entirely (E2B/Daytona-class
 *       remote sandbox). Strongest isolation from the local machine.</li>
 * </ul>
 */
public enum IsolationTier {

    PROCESS,
    CONTAINER,
    MICRO_VM,
    REMOTE;

    /**
     * @return {@code true} when this tier provides at least as much isolation as
     *         {@code floor} (i.e. is no weaker).
     */
    public boolean isAtLeast(IsolationTier floor) {
        return floor == null || this.ordinal() >= floor.ordinal();
    }
}
