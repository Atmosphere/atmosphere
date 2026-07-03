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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a tool that must run against a {@link Sandbox} instead
 * of touching the hosting JVM. The tool layer reads this annotation through
 * the {@code org.atmosphere.ai.tool.ToolSandboxBinding} SPI (implemented
 * here by {@link SandboxToolBinding}): at each invocation the framework
 * provisions a {@link Sandbox} from the members below — provider chosen by
 * {@link #backend()} name, {@link SandboxLimits} from image / CPU / memory /
 * wall-time / network — injects it into the method as a parameter of type
 * {@link Sandbox}, and closes it when the invocation completes (success,
 * exception, or timeout alike). The injected sandbox is framework-owned: the
 * method must not close it.
 *
 * <p>When the named backend is not available the invocation fails fast with
 * a descriptive error naming the backend and how to enable it — there is no
 * silent fallback to in-JVM execution (per v0.6 plan open question #2).</p>
 *
 * <p>Use alongside {@code @AiTool} for tool metadata, or on a
 * {@code @Prompt} method.</p>
 *
 * @see Sandbox
 * @see SandboxToolBinding
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SandboxTool {

    /**
     * Preferred {@link SandboxProvider#name()} for this tool
     * ({@code "docker"} by default). When the named provider is not
     * available the invocation fails hard.
     */
    String backend() default "docker";

    /**
     * Backend-specific image reference. Required. For Docker this is a
     * container image (e.g. {@code "ubuntu:24.04"}); for in-process it is
     * a tag understood by the in-process provider.
     */
    String image();

    /** CPU fraction. Defaults to {@link SandboxLimits#DEFAULT}'s 1.0. */
    double cpuFraction() default 1.0;

    /** Memory bytes. Defaults to 512 MB. */
    long memoryBytes() default 512L * 1024L * 1024L;

    /** Wall-time seconds. Defaults to 300 (5 minutes). */
    long wallTimeSeconds() default 300L;

    /**
     * Whether the sandbox may reach the network. Default {@code false}.
     * Maps to {@link NetworkPolicy#NONE} ({@code false}) or
     * {@link NetworkPolicy#FULL} ({@code true}) — the two modes the Docker
     * backend actually enforces; the label-only {@code GIT_ONLY} /
     * {@code ALLOWLIST} tiers are not reachable from this annotation.
     */
    boolean network() default false;
}
