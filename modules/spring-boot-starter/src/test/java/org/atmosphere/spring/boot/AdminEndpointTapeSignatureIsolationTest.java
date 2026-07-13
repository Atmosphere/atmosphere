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
package org.atmosphere.spring.boot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Regression for the {@code NoClassDefFoundError: org/atmosphere/ai/tape/*}
 * startup crash on samples without the optional {@code atmosphere-ai}
 * dependency (e.g. {@code spring-boot-durable-sessions}).
 *
 * <p>{@link AtmosphereAdminEndpoint} and {@link AtmosphereConsoleInfoEndpoint}
 * are always-active surfaces ({@code @ConditionalOnBean(AtmosphereAdmin)} /
 * unconditional). Spring reflects over every one of their methods at bean
 * registration; {@link Class#getDeclaredMethods()} force-loads each method's
 * parameter and return types. A tape type in one of those signatures therefore
 * throws {@link NoClassDefFoundError} at startup wherever the tape package is
 * absent. The tape reads must live in {@link TapeAdminSupport} (loaded only
 * from a classpath-guarded body), never in a controller signature.
 *
 * <p>This test performs the exact reflection Spring does and asserts no tape
 * type leaks into a signature — re-introducing {@code TapeStatus parseTapeStatus(..)}
 * or {@code tapeRunToMap(TapeRun)} on a controller breaks the build here rather
 * than at a downstream sample's startup.
 */
class AdminEndpointTapeSignatureIsolationTest {

    private static final String TAPE_PACKAGE = "org.atmosphere.ai.tape.";

    @Test
    void adminEndpointExposesNoTapeTypeInMethodSignatures() {
        assertNoTapeSignatures(AtmosphereAdminEndpoint.class);
    }

    @Test
    void consoleInfoEndpointExposesNoTapeTypeInMethodSignatures() {
        assertNoTapeSignatures(AtmosphereConsoleInfoEndpoint.class);
    }

    private static void assertNoTapeSignatures(Class<?> controller) {
        List<String> offenders = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            if (m.getReturnType().getName().startsWith(TAPE_PACKAGE)) {
                offenders.add(m.getName() + " returns " + m.getReturnType().getName());
            }
            for (Class<?> p : m.getParameterTypes()) {
                if (p.getName().startsWith(TAPE_PACKAGE)) {
                    offenders.add(m.getName() + " takes " + p.getName());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                controller.getSimpleName() + " must keep optional atmosphere-ai tape types out of "
                        + "method signatures (Spring force-loads them at startup, crashing samples "
                        + "without atmosphere-ai); offenders: " + offenders);
    }
}
