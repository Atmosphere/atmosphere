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

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtmosphereAnnotationsTest {

    @Test
    void coreAnnotationsReturnsNonEmptyList() {
        var annotations = AtmosphereAnnotations.coreAnnotations();
        assertNotNull(annotations);
        assertEquals(24, annotations.size());
    }

    @Test
    void coreAnnotationsAreAllAnnotationTypes() {
        for (Class<? extends Annotation> clazz : AtmosphereAnnotations.coreAnnotations()) {
            assertEquals(true, clazz.isAnnotation());
        }
    }

    @Test
    void coreAnnotationsIsUnmodifiable() {
        var annotations = AtmosphereAnnotations.coreAnnotations();
        assertThrows(UnsupportedOperationException.class,
                () -> annotations.add(Override.class));
    }

    @Test
    void coreAnnotationNamesMatchesAnnotationClasses() {
        var annotations = AtmosphereAnnotations.coreAnnotations();
        var names = AtmosphereAnnotations.coreAnnotationNames();
        assertEquals(annotations.size(), names.size());
        for (int i = 0; i < annotations.size(); i++) {
            assertEquals(annotations.get(i).getName(), names.get(i));
        }
    }

    @Test
    void coreAnnotationNamesReturnsNonEmptyList() {
        var names = AtmosphereAnnotations.coreAnnotationNames();
        assertNotNull(names);
        assertEquals(24, names.size());
    }

    @Test
    void coreAnnotationNamesContainsExpectedEntries() {
        var names = AtmosphereAnnotations.coreAnnotationNames();
        assertEquals(true, names.contains(
                "org.atmosphere.config.service.AtmosphereHandlerService"));
        assertEquals(true, names.contains(
                "org.atmosphere.config.service.ManagedService"));
        assertEquals(true, names.contains(
                "org.atmosphere.config.service.WebSocketHandlerService"));
    }

    @Test
    void coreAnnotationsReturnsSameInstanceOnMultipleCalls() {
        var first = AtmosphereAnnotations.coreAnnotations();
        var second = AtmosphereAnnotations.coreAnnotations();
        assertEquals(first, second);
    }

    @Test
    void coreAnnotationsContainsNoDuplicates() {
        var annotations = AtmosphereAnnotations.coreAnnotations();
        long distinctCount = annotations.stream().distinct().count();
        assertEquals(annotations.size(), distinctCount);
    }

    @Test
    void coreAnnotationNamesContainsNoDuplicates() {
        var names = AtmosphereAnnotations.coreAnnotationNames();
        long distinctCount = names.stream().distinct().count();
        assertEquals(names.size(), distinctCount);
    }
}
