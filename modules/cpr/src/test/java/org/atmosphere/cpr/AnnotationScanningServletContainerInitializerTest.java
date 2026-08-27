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

import jakarta.servlet.annotation.HandlesTypes;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code @HandlesTypes} list on {@link AnnotationScanningServletContainerInitializer} must
 * carry compile-time class literals, so it cannot delegate to {@link AtmosphereAnnotations}.
 * That duplication is what let {@code @RoomService} reach {@code coreAnnotations()} without ever
 * reaching the servlet scan — a WAR deployment silently discovered no {@code @RoomService} class.
 *
 * <p>This test is the gate that keeps the hand-written copy honest.</p>
 */
public class AnnotationScanningServletContainerInitializerTest {

    private static Set<Class<? extends Annotation>> handlesTypes() {
        HandlesTypes h = AnnotationScanningServletContainerInitializer.class.getAnnotation(HandlesTypes.class);
        assertNotNull(h, "@HandlesTypes is missing from AnnotationScanningServletContainerInitializer");

        Set<Class<? extends Annotation>> declared = new LinkedHashSet<>();
        for (Class<?> c : h.value()) {
            assertTrue(c.isAnnotation(), c.getName() + " is listed in @HandlesTypes but is not an annotation");
            @SuppressWarnings("unchecked") // guarded by the isAnnotation() assertion above
            Class<? extends Annotation> a = (Class<? extends Annotation>) c;
            declared.add(a);
        }
        return declared;
    }

    @Test
    public void handlesTypesCoversEveryCoreAnnotation() {
        Set<Class<? extends Annotation>> declared = handlesTypes();
        List<Class<? extends Annotation>> core = AtmosphereAnnotations.coreAnnotations();

        Set<Class<? extends Annotation>> missing = new LinkedHashSet<>(core);
        missing.removeAll(declared);

        assertTrue(missing.isEmpty(),
                "@HandlesTypes is missing " + missing.size() + " annotation(s) that AtmosphereAnnotations."
                        + "coreAnnotations() declares, so a WAR/SCI deployment will never discover them: " + missing);
    }

    @Test
    public void handlesTypesDeclaresNothingBeyondCoreAnnotations() {
        Set<Class<? extends Annotation>> declared = handlesTypes();

        Set<Class<? extends Annotation>> extra = new LinkedHashSet<>(declared);
        AtmosphereAnnotations.coreAnnotations().forEach(extra::remove);

        assertTrue(extra.isEmpty(),
                "@HandlesTypes declares annotation(s) absent from AtmosphereAnnotations.coreAnnotations(), so the "
                        + "servlet scan and the bytecode scan disagree: " + extra);
    }

    @Test
    public void handlesTypesHasNoDuplicates() {
        HandlesTypes h = AnnotationScanningServletContainerInitializer.class.getAnnotation(HandlesTypes.class);
        assertNotNull(h);
        assertEquals(h.value().length, handlesTypes().size(),
                "@HandlesTypes lists the same annotation more than once");
    }
}
