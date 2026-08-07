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
package org.atmosphere.cpr;

import org.atmosphere.config.AtmosphereAnnotation;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every {@code @AtmosphereAnnotation} processor into the reflective-type
 * registry by walking the package, not by trusting a hand-written list.
 *
 * <p>Processors are constructed through
 * {@code framework.newClassInstance(Processor.class, …)}, so one that carries no
 * reflection hint cannot be built in a native image, and the annotation it
 * handles stops working there — while every other annotation keeps working,
 * which makes the gap look like an unrelated bug in one feature.</p>
 *
 * <p>This test exists because that happened. {@code RoomServiceProcessor} was the
 * only processor missing from {@code annotationProcessors()}, so {@code @RoomService}
 * was unregistered while the other 22 were fine. The list had been maintained by
 * hand, and the test guarding it asserted only that it was non-empty and that its
 * entries resolved — both true of an incomplete list. A gate that cannot detect
 * the omission it exists to prevent is not a gate.</p>
 */
class AnnotationProcessorRegistrationTest {

    private static final String PACKAGE = "org.atmosphere.annotation";

    /** Concrete {@code @AtmosphereAnnotation}-carrying processors found on disk. */
    private static List<Class<?>> declaredProcessors() {
        var dir = new File("src/main/java/org/atmosphere/annotation");
        assertTrue(dir.isDirectory(),
                "expected processor sources at " + dir.getAbsolutePath()
                        + " — if they moved, fix this walk rather than deleting the test");

        var found = new ArrayList<Class<?>>();
        var files = dir.listFiles((d, name) -> name.endsWith("Processor.java"));
        if (files == null) {
            return found;
        }
        for (var file : files) {
            var simple = file.getName().substring(0, file.getName().length() - ".java".length());
            Class<?> clazz;
            try {
                clazz = Class.forName(PACKAGE + "." + simple);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            if (!clazz.isAnnotationPresent(AtmosphereAnnotation.class)) {
                continue;
            }
            found.add(clazz);
        }
        return found;
    }

    @Test
    void thereAreProcessorsToCheck() {
        assertFalse(declaredProcessors().isEmpty(),
                "finding no annotated processors means the walk is broken, and this test "
                        + "would pass vacuously — which is exactly how the gap it guards "
                        + "against went unnoticed");
    }

    @Test
    void everyAnnotationProcessorOnDiskIsRegisteredForReflection() {
        var registered = AtmosphereReflectiveTypes.annotationProcessors();

        var missing = new ArrayList<String>();
        for (var processor : declaredProcessors()) {
            if (!registered.contains(processor.getName())) {
                missing.add(processor.getName());
            }
        }

        assertEquals(List.of(), missing,
                "these processors are instantiated reflectively but carry no registration, "
                        + "so the annotation each one handles silently stops working in a "
                        + "native image while the rest keep working");
    }

    @Test
    void roomServiceRemainsRegistered() {
        // Named explicitly: this is the processor that was missing, and a
        // regression here would take @RoomService, presence and room history
        // out of every native image without touching any other feature.
        assertTrue(AtmosphereReflectiveTypes.annotationProcessors()
                        .contains(PACKAGE + ".RoomServiceProcessor"),
                "@RoomService is discovered through RoomServiceProcessor; without a "
                        + "reflection hint the processor cannot be constructed natively");
    }

    @Test
    void theRegistryNamesNothingThatDoesNotExist() {
        var unresolvable = new ArrayList<String>();
        for (var name : AtmosphereReflectiveTypes.annotationProcessors()) {
            try {
                Class.forName(name, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                unresolvable.add(name);
            }
        }
        assertEquals(List.of(), unresolvable,
                "a registered name that resolves to nothing is dead weight in the shipped "
                        + "metadata and a sign the list has gone stale in the other direction");
    }
}
