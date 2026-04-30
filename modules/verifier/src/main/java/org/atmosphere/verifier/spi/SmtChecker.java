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
package org.atmosphere.verifier.spi;

import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.policy.Policy;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * SPI for SMT-backed proof checking — the long tail of
 * {@code verifier} ambitions. Where the structural verifiers
 * (allowlist, well-formed, taint, capability, automaton) catch the
 * canonical bugs cheaply, an SMT checker proves richer invariants:
 *
 * <ul>
 *   <li>"For every reachable plan, the running-cost field stays under the
 *       budget."</li>
 *   <li>"No reachable plan emits more than N tool calls in any
 *       transitively-tainted path."</li>
 *   <li>"For every plan that passes the rest of the chain, the
 *       conjunction of declared invariants holds in Z3."</li>
 * </ul>
 *
 * <p>Implementations are discovered via {@link ServiceLoader}. The
 * shipped default is the {@link NoOpSmtChecker} — the SPI is in place
 * so a Z3-backed implementation can be dropped in without touching
 * {@link org.atmosphere.verifier.checks.SmtVerifier}'s call site.</p>
 *
 * <p>Why a separate SPI from {@link PlanVerifier}? Two reasons:</p>
 * <ol>
 *   <li>SMT checkers have a different lifecycle — they may need to spin
 *       up a solver process, share a context across calls, or release
 *       native resources. A plain {@link PlanVerifier} doesn't model
 *       any of that.</li>
 *   <li>Multiple {@link PlanVerifier}s can run; only one
 *       {@link SmtChecker} is selected (highest priority that
 *       reports {@link #isAvailable()}). The verifier wraps it as a
 *       single chain entry.</li>
 * </ol>
 */
public interface SmtChecker {

    /**
     * Stable identifier for diagnostics (e.g. {@code "noop"}, {@code "z3"}).
     */
    String name();

    /**
     * Whether this checker's required dependencies are present at
     * runtime. The Z3 backend, for example, returns {@code true} only
     * when the native library has loaded successfully — never when
     * the JAR is on the classpath but the {@code .dylib}/{@code .so}
     * is missing (Correctness Invariant #5: runtime truth, not
     * classpath presence).
     */
    boolean isAvailable();

    /**
     * Higher wins. The shipped {@link NoOpSmtChecker} uses priority 0;
     * a Z3 backend uses 100; future Lean / Verifast / etc. backends
     * pick a tier above whatever they replace.
     */
    default int priority() {
        return 0;
    }

    /**
     * Run any SMT-level proofs declared by the policy. Returns the
     * aggregate {@link VerificationResult}; an empty violation list is
     * a successful proof.
     *
     * <p>Implementations must be pure functions of their inputs:
     * the same {@code (workflow, policy)} pair must produce the same
     * result. Solver scratch state may be cached internally but must
     * not survive across calls in a way that affects results.</p>
     */
    VerificationResult check(Workflow workflow, Policy policy);

    /**
     * Resolve the highest-priority available checker via
     * {@link ServiceLoader}. Falls back to the {@link NoOpSmtChecker}
     * when nothing else is on the classpath, so call sites never need
     * to null-check.
     */
    static SmtChecker resolve() {
        return ServiceLoader.load(SmtChecker.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(SmtChecker::isAvailable)
                .max(Comparator.comparingInt(SmtChecker::priority))
                .orElseGet(NoOpSmtChecker::new);
    }

    /**
     * Built-in default — always available, always succeeds. The
     * {@link org.atmosphere.verifier.checks.SmtVerifier} bound to this
     * checker is a safe no-op that reports green so the chain
     * composition stays uniform whether or not a real solver is on the
     * classpath.
     */
    final class NoOpSmtChecker implements SmtChecker {
        @Override public String name() { return "noop"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public VerificationResult check(Workflow workflow, Policy policy) {
            return VerificationResult.of(List.of());
        }
    }
}
