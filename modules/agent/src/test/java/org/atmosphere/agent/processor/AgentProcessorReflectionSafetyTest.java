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
package org.atmosphere.agent.processor;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the {@code NoClassDefFoundError: AgentCard} that broke
 * the orchestration-demo sample boot: Atmosphere's annotation-scanning
 * injector ({@code Utils.getInheritedPrivateMethod} →
 * {@code InjectableObjectFactory.applyMethods}) reflects over every scanned
 * class's declared methods and resolves their parameter and return types. If
 * {@link AgentProcessor} exposes a method whose signature references an
 * {@code org.atmosphere.a2a} type, that reflection force-loads the A2A classes
 * on the classpath of samples that do not include {@code atmosphere-a2a},
 * aborting registration of every agent.
 *
 * <p>A2A is an optional dependency: all A2A-typed helpers live in
 * {@link A2aCardDecorations}, which is loaded only inside an
 * already-{@code A2a}-guarded branch. This test pins the invariant that
 * {@link AgentProcessor}'s own method signatures stay A2A-free.</p>
 */
class AgentProcessorReflectionSafetyTest {

    @Test
    void agentProcessorHasNoA2aTypesInMethodSignatures() {
        var offenders = new ArrayList<String>();
        for (Method method : AgentProcessor.class.getDeclaredMethods()) {
            var types = new ArrayList<Class<?>>();
            types.add(method.getReturnType());
            for (var p : method.getParameterTypes()) {
                types.add(p);
            }
            for (var type : types) {
                var name = type.getName();
                // Unwrap array component types (e.g. AgentCard[]).
                while (name.startsWith("[")) {
                    type = type.getComponentType();
                    name = type.getName();
                }
                if (name.startsWith("org.atmosphere.a2a")) {
                    offenders.add(method.getName() + " -> " + name);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "AgentProcessor method signatures must not reference org.atmosphere.a2a types "
                        + "(injector reflection force-loads them where a2a is absent): " + offenders);
    }
}
