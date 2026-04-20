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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link DelegatingStreamingSession} → {@link StreamingSession}
 * forwarding contract by reflection. Every non-synthetic, non-private
 * method declared on {@code StreamingSession} must be overridden on
 * {@code DelegatingStreamingSession} — otherwise a new method added
 * to the interface would fall back to the default implementation,
 * silently shadowing the underlying session's behaviour (the class
 * of bug {@link org.atmosphere.ai.resume.RunEventCapturingSession} hit
 * on {@link StreamingSession#handoff(String, String)}).
 *
 * <p>If this test fails, add a forwarding override to
 * {@code DelegatingStreamingSession}. Do not suppress. The whole
 * point of the base class is to make decorator-level regressions
 * impossible.</p>
 */
class DelegatingStreamingSessionContractTest {

    @Test
    void everyStreamingSessionMethodIsForwardedByDelegatingBase() {
        var ifaceMethods = Arrays.stream(StreamingSession.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .filter(m -> !m.isBridge())
                .filter(m -> !Modifier.isPrivate(m.getModifiers()))
                .filter(m -> !m.getName().startsWith("lambda$"))
                .toList();
        assertTrue(ifaceMethods.size() >= 15,
                "Sanity — StreamingSession should expose at least 15 methods, "
                + "got " + ifaceMethods.size() + ". If this fails the reflection "
                + "filter is probably broken, not the contract.");

        var missing = new ArrayList<String>();
        for (var m : ifaceMethods) {
            try {
                Method override = DelegatingStreamingSession.class.getDeclaredMethod(
                        m.getName(), m.getParameterTypes());
                assertNotNull(override,
                        "DelegatingStreamingSession must declare a forward override "
                        + "for " + signature(m));
            } catch (NoSuchMethodException e) {
                missing.add(signature(m));
            }
        }
        assertEquals(List.of(), missing,
                "DelegatingStreamingSession is missing forward overrides for:\n  "
                + String.join("\n  ", missing)
                + "\nAdd each one with a single-line delegate.X(...) body so a "
                + "future decorator that extends DelegatingStreamingSession "
                + "inherits a working forward instead of the interface default.");
    }

    private static String signature(Method m) {
        var params = Arrays.stream(m.getParameterTypes())
                .map(Class::getSimpleName)
                .toList();
        return m.getReturnType().getSimpleName() + " " + m.getName()
                + "(" + String.join(", ", params) + ")";
    }
}
