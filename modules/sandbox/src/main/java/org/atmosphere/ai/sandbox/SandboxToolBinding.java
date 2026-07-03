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

import org.atmosphere.ai.tool.ToolSandboxBinding;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ToolSandboxBinding} implementation for {@link SandboxTool}. Claims
 * any method carrying the annotation; each {@link #open(Method)} resolves the
 * {@link SandboxProvider} named by {@link SandboxTool#backend()} from the
 * ServiceLoader set, builds {@link SandboxLimits} from the members, creates
 * the {@link Sandbox}, and hands it back as the scope's injectable. The tool
 * layer closes the scope after the invocation; the sandbox never outlives the
 * call it was provisioned for.
 *
 * <p>Fail-fast: when the named backend is missing or reports
 * {@link SandboxProvider#isAvailable() unavailable}, {@link #open(Method)}
 * throws with a message naming the backend and how to enable it. Another
 * available provider is never substituted — no in-JVM (or cross-backend)
 * fallback (Correctness Invariant #6, fail closed).</p>
 *
 * <p>Network mapping: {@code network = false} → {@link NetworkPolicy#NONE},
 * {@code network = true} → {@link NetworkPolicy#FULL}. The intermediate
 * {@code GIT_ONLY} / {@code ALLOWLIST} modes are label-only in
 * {@link DockerSandboxProvider} (enforced by an external egress firewall, not
 * by the container runtime), so the annotation deliberately exposes only the
 * two enforced modes.</p>
 */
public final class SandboxToolBinding implements ToolSandboxBinding {

    @Override
    public boolean appliesTo(Method method) {
        return method != null && method.isAnnotationPresent(SandboxTool.class);
    }

    @Override
    public SandboxScope open(Method method) {
        var annotation = method != null ? method.getAnnotation(SandboxTool.class) : null;
        if (annotation == null) {
            throw new IllegalArgumentException(
                    "@SandboxTool is not present on " + describe(method));
        }
        var provider = resolveProvider(annotation.backend(), describe(method));
        var sandbox = provider.create(annotation.image(), limitsFrom(annotation), Map.of(
                "owner", "sandbox-tool",
                "tool.method", describe(method)));
        return new Scope(sandbox);
    }

    /**
     * Annotation → limits mapping. Package-private so the mapping is pinned
     * by a test independent of any provider.
     */
    static SandboxLimits limitsFrom(SandboxTool annotation) {
        return new SandboxLimits(
                annotation.cpuFraction(),
                annotation.memoryBytes(),
                Duration.ofSeconds(annotation.wallTimeSeconds()),
                annotation.network() ? NetworkPolicy.FULL : NetworkPolicy.NONE);
    }

    /**
     * Resolve the provider named {@code backend} from the ServiceLoader set,
     * requiring {@link SandboxProvider#isAvailable()}. Runtime truth
     * (Invariant #5): the error names the provider that is actually missing
     * or down, never a guess.
     */
    private static SandboxProvider resolveProvider(String backend, String methodRef) {
        SandboxProvider named = null;
        var registered = new ArrayList<String>();
        for (var provider : ServiceLoader.load(SandboxProvider.class)) {
            registered.add(provider.name());
            if (provider.name().equals(backend)) {
                named = provider;
            }
        }
        if (named == null) {
            throw new IllegalStateException("@SandboxTool on " + methodRef
                    + " requires sandbox backend '" + backend + "', but no SandboxProvider "
                    + "with that name is registered (registered: " + registered + "). "
                    + "Add the backend's module to the classpath — sandboxed tools never "
                    + "fall back to in-JVM execution.");
        }
        if (!named.isAvailable()) {
            throw new IllegalStateException("@SandboxTool on " + methodRef
                    + " requires sandbox backend '" + backend + "', but it is not available: "
                    + availabilityHint(named) + " Sandboxed tools never fall back to "
                    + "in-JVM execution.");
        }
        return named;
    }

    private static String availabilityHint(SandboxProvider provider) {
        return switch (provider.name()) {
            case "docker" -> "ensure `docker` is on PATH and the daemon is running.";
            case "in-process" -> "enable the dev-only opt-in with -D"
                    + InProcessSandboxProvider.INSECURE_OPT_IN
                    + "=true (NOT a security boundary).";
            default -> "the provider reported isAvailable() = false.";
        };
    }

    private static String describe(Method method) {
        if (method == null) {
            return "<null method>";
        }
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    /**
     * Per-invocation scope over one framework-owned {@link Sandbox}. Close is
     * idempotent at this layer too (belt to the {@link Sandbox#close()}
     * contract's suspenders) so double-close from a racing terminal path is
     * harmless.
     */
    private static final class Scope implements SandboxScope {

        private final Sandbox sandbox;
        private final AtomicBoolean closed = new AtomicBoolean();

        Scope(Sandbox sandbox) {
            this.sandbox = sandbox;
        }

        @Override
        public Class<?> injectableType() {
            return Sandbox.class;
        }

        @Override
        public Object injectable() {
            return sandbox;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                sandbox.close();
            }
        }
    }
}
