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
 * {@link org.atmosphere.verifier.spi.SmtChecker} backed by SMTInterpol — a
 * pure-JVM SMT solver bundled transitively by java-smt. It requires no native
 * library, so it loads on every OS/architecture (including Apple Silicon) and
 * in CI with zero setup. This is the shipped default backend.
 *
 * <p>Priority {@code 100}: selected by
 * {@link org.atmosphere.verifier.spi.SmtChecker#resolve()} over the no-op
 * default ({@code 0}), but yields to a native {@link Z3SmtChecker} ({@code 200})
 * when its libraries are present.</p>
 *
 * @see AbstractJavaSmtChecker for the proof logic shared with {@link Z3SmtChecker}
 */
public final class SmtInterpolChecker extends AbstractJavaSmtChecker {

    @Override
    public String name() {
        return "smtinterpol";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    protected Solvers solver() {
        return Solvers.SMTINTERPOL;
    }
}
