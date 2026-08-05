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
package org.atmosphere.checkpoint.spring;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the GraalVM reflection contract for lifecycle callbacks declared with
 * {@code @Bean(initMethod = …, destroyMethod = …)}.
 *
 * <p>Spring AOT derives those hints from the factory method's <em>declared</em>
 * return type, but the container invokes them on {@code bean.getClass()}. When
 * the two differ — a factory declaring the {@code CheckpointStore} interface and
 * returning an {@code InMemoryCheckpointStore} — AOT registers the interface
 * method while the runtime looks up the concrete one, whose declared methods are
 * absent from the image. Reflective lookup then quietly returns nothing and the
 * context dies at startup with "Could not find an init method named 'start'".</p>
 *
 * <p>Nothing on the JVM notices: full reflection makes the mismatch invisible,
 * so this only ever surfaced under Native Image, and only once a native lane
 * exercised a sample that actually depends on {@code atmosphere-checkpoint}.
 * This test moves the failure back to a plain unit run.</p>
 */
class CheckpointAutoConfigurationAotHintsTest {

    /** Factory methods that hand their lifecycle to the container. */
    private static List<Method> lifecycleBeanMethods() {
        var found = new ArrayList<Method>();
        for (var method : AtmosphereCheckpointAutoConfiguration.class.getDeclaredMethods()) {
            var bean = method.getAnnotation(Bean.class);
            if (bean == null) {
                continue;
            }
            if (!bean.initMethod().isEmpty() || !isInferredDestroy(bean.destroyMethod())) {
                found.add(method);
            }
        }
        return found;
    }

    /** Spring's default {@code destroyMethod} means "infer", not "none declared". */
    private static boolean isInferredDestroy(String destroyMethod) {
        return AbstractBeanDefinitionDefaults.INFER.equals(destroyMethod) || destroyMethod.isEmpty();
    }

    /** Mirror of Spring's inference sentinel, kept local so the test owns no Spring internals. */
    private static final class AbstractBeanDefinitionDefaults {
        private static final String INFER = "(inferred)";

        private AbstractBeanDefinitionDefaults() {
        }
    }

    @Test
    void theConfigurationStillDeclaresALifecycleBean() {
        assertFalse(lifecycleBeanMethods().isEmpty(),
                "this test is only meaningful while a @Bean delegates start/stop to the "
                        + "container — if that stopped being true, delete it rather than "
                        + "letting it pass vacuously");
    }

    @Test
    void everyLifecycleMethodResolvesOnAConcreteReturnType() {
        for (var method : lifecycleBeanMethods()) {
            assertFalse(method.getReturnType().isInterface(),
                    "@Bean " + method.getName() + " declares a lifecycle method but returns the "
                            + "interface " + method.getReturnType().getSimpleName() + ". Spring AOT "
                            + "registers the reflection hint against this declared type, so the "
                            + "concrete class the container actually invokes would be missing from "
                            + "the native image. Declare the implementation type.");
        }
    }

    @Test
    void theHintedMethodIsTheOneTheContainerWillInvoke() {
        for (var method : lifecycleBeanMethods()) {
            var bean = method.getAnnotation(Bean.class);
            var returnType = method.getReturnType();

            var names = new ArrayList<String>();
            if (!bean.initMethod().isEmpty()) {
                names.add(bean.initMethod());
            }
            if (!isInferredDestroy(bean.destroyMethod())) {
                names.add(bean.destroyMethod());
            }

            for (var name : names) {
                var lifecycle = assertDoesNotThrow(() -> returnType.getMethod(name),
                        "@Bean " + method.getName() + " names '" + name + "' but "
                                + returnType.getSimpleName() + " has no such public method");

                assertFalse(lifecycle.getDeclaringClass().isInterface(),
                        "'" + name + "' resolves to " + lifecycle.getDeclaringClass().getSimpleName()
                                + ", an interface. That is the hint AOT emits, but the container "
                                + "invokes the override on the concrete bean class — the exact "
                                + "mismatch that failed the native image at startup.");

                assertTrue(returnType.isAssignableFrom(lifecycle.getDeclaringClass())
                                || lifecycle.getDeclaringClass().isAssignableFrom(returnType),
                        "'" + name + "' must resolve within the declared type's own hierarchy so "
                                + "the registered hint and the invoked method are the same one");
            }
        }
    }
}
