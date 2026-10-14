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
package org.atmosphere.verifier;

import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.SmtChecker;
import org.atmosphere.verifier.spi.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SmtChecker#resolve()} runs on every admin verifier-summary request.
 * It used to instantiate every ServiceLoader provider and probe its
 * availability each time, so a native backend (Z3) re-attempted its library
 * load per request, logging a stack trace at debug each time. Resolution is
 * done once and reused.
 */
class SmtCheckerResolveOnceTest {

    /**
     * Registered through {@code META-INF/services} in test resources. Reports
     * unavailable, like a native solver whose library does not load, so the
     * resolution still falls back to the no-op checker.
     */
    public static final class ProbeCountingChecker implements SmtChecker {
        static final AtomicInteger PROBES = new AtomicInteger();

        @Override public String name() { return "probe-counting"; }
        @Override public boolean isAvailable() {
            PROBES.incrementAndGet();
            return false;
        }
        @Override public int priority() { return 300; }
        @Override public VerificationResult check(Workflow workflow, Policy policy) {
            return VerificationResult.of(List.of());
        }
    }

    @Test
    void repeatedResolutionReusesTheCheckerAndProbesAtMostOnce() {
        var first = SmtChecker.resolve();
        for (int i = 0; i < 5; i++) {
            assertSame(first, SmtChecker.resolve(), "resolve() must reuse the resolved checker");
        }
        assertTrue(ProbeCountingChecker.PROBES.get() <= 1,
                "providers are probed once per JVM, not per call: "
                        + ProbeCountingChecker.PROBES.get() + " probes");
    }
}
