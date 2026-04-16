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
/**
 * Isolated execution primitive for Atmosphere agents.
 *
 * <p>Ships:</p>
 * <ul>
 *   <li>{@link org.atmosphere.ai.sandbox.Sandbox} SPI and companion records
 *       ({@link org.atmosphere.ai.sandbox.SandboxExec},
 *       {@link org.atmosphere.ai.sandbox.SandboxLimits},
 *       {@link org.atmosphere.ai.sandbox.SandboxSnapshot})</li>
 *   <li>{@link org.atmosphere.ai.sandbox.SandboxTool} annotation for
 *       binding {@code @AiTool} methods to a sandbox backend</li>
 *   <li>{@link org.atmosphere.ai.sandbox.DockerSandboxProvider} — default
 *       production backend via the Docker CLI</li>
 *   <li>{@link org.atmosphere.ai.sandbox.InProcessSandboxProvider} —
 *       dev-only reference backend, <b>not</b> a security boundary</li>
 * </ul>
 *
 * <p>Third-party backends (Firecracker, Kata, Vercel Sandbox, E2B, Modal,
 * Blaxel) ship in separate modules and plug in via the SPI.</p>
 */
package org.atmosphere.ai.sandbox;
