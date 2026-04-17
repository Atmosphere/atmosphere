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

import java.util.Map;

/**
 * Factory for {@link Sandbox} instances. One provider per backend
 * implementation (Docker, Firecracker, in-process, etc.). Discovered via
 * {@link java.util.ServiceLoader} so third-party backends register
 * themselves through the same mechanism as built-ins.
 */
public interface SandboxProvider {

    /** Stable short name for this backend ({@code "docker"}, {@code "in-process"}). */
    String name();

    /**
     * Whether the backend is currently usable. For example, the Docker
     * provider returns {@code false} when {@code docker} is not on
     * {@code PATH} or the daemon is unreachable. Capability truth
     * (Correctness Invariant #5).
     */
    boolean isAvailable();

    /**
     * Create a fresh sandbox with the given limits and image. The
     * {@code image} is backend-specific — for Docker it is a container
     * image reference, for in-process it may be a tag like
     * {@code "jvm"}. Implementations document which images they accept.
     *
     * @param image    backend-specific image reference
     * @param limits   resource bounds (use {@link SandboxLimits#DEFAULT} for the v0.6 defaults)
     * @param metadata optional labels surfaced on the admin control plane
     */
    Sandbox create(String image, SandboxLimits limits, Map<String, String> metadata);
}
