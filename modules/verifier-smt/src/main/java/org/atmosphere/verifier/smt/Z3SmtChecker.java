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
package org.atmosphere.verifier.smt;

import org.sosy_lab.java_smt.SolverContextFactory.Solvers;

/**
 * {@link org.atmosphere.verifier.spi.SmtChecker} backed by the native Z3 solver
 * (MIT-licensed) via java-smt. Z3 is faster and more capable than SMTInterpol,
 * but requires native libraries on {@code java.library.path}; it is therefore
 * <em>opt-in</em>.
 *
 * <p>{@link #isAvailable()} returns {@code true} only when both the Z3 JNI
 * bindings ({@code javasmt-solver-z3}) and the platform native libraries
 * ({@code libz3} + {@code libz3java}) actually load — never on classpath
 * presence alone (Correctness Invariant #5). When the natives are absent the
 * checker reports unavailable and {@link org.atmosphere.verifier.spi.SmtChecker#resolve()}
 * transparently falls back to {@link SmtInterpolChecker}.</p>
 *
 * <p>Priority {@code 200}: when Z3's natives are present it is selected over
 * the pure-JVM {@link SmtInterpolChecker} ({@code 100}). The two backends share
 * identical proof logic (see {@link AbstractJavaSmtChecker}), so enabling Z3
 * changes only the solver engine, not the verified semantics. See the module
 * README for the exact Maven coordinates and {@code java.library.path} setup
 * per platform (Linux/macOS-x64/macOS-arm64/Windows).</p>
 */
public final class Z3SmtChecker extends AbstractJavaSmtChecker {

    @Override
    public String name() {
        return "z3";
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    protected Solvers solver() {
        return Solvers.Z3;
    }
}
