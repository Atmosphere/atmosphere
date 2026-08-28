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
package org.atmosphere.samples.springboot.lowlevel;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.Delete;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Post;
import org.atmosphere.config.service.Put;
import org.atmosphere.config.service.Resume;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.room.auth.RoomAuth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This sample claims the two feeds answer the same verbs at different layers, and that
 * {@code @RoomAuth} can only live on the raw one. Both claims are asserted here rather than
 * only stated in the README.
 */
class RawVsManagedParityTest {

    private static boolean hasMethodAnnotated(Class<?> type, Class<? extends Annotation> ann) {
        return Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.isAnnotationPresent(ann));
    }

    @Test
    void theRawHandlerIsItselfTheRegisteredHandler() {
        assertTrue(AtmosphereHandler.class.isAssignableFrom(OpsFeedHandler.class),
                "@AtmosphereHandlerService registers this class directly — it must BE an AtmosphereHandler");
        assertNotNull(OpsFeedHandler.class.getAnnotation(AtmosphereHandlerService.class));
    }

    @Test
    void roomAuthSitsOnTheRawHandlerBecauseThatIsTheOnlyPlaceItResolves() {
        RoomAuth auth = OpsFeedHandler.class.getAnnotation(RoomAuth.class);
        assertNotNull(auth, "@RoomAuth belongs on the registered handler class");
        assertEquals(OncallRoomAuthorizer.class, auth.authorizer());
    }

    @Test
    void roomAuthIsAbsentFromTheManagedTwinOnPurpose() {
        // RoomProtocolInterceptor.scanAuthorizer reads @RoomAuth off the REGISTERED handler.
        // For a @ManagedService POJO that is a ManagedAtmosphereHandler wrapper, so the
        // annotation would be silently ignored — worse than absent, because it reads as
        // protection that is not there.
        assertFalse(ManagedOpsFeed.class.isAnnotationPresent(RoomAuth.class),
                "@RoomAuth on a @ManagedService POJO installs no authorizer and silently reads as security");
    }

    @Test
    void bothFeedsAnswerTheSameHttpVerbs() {
        // The managed side declares the verbs as annotations...
        assertTrue(hasMethodAnnotated(ManagedOpsFeed.class, Get.class), "managed feed must answer GET");
        assertTrue(hasMethodAnnotated(ManagedOpsFeed.class, Post.class), "managed feed must answer POST");
        assertTrue(hasMethodAnnotated(ManagedOpsFeed.class, Put.class), "managed feed must answer PUT");
        assertTrue(hasMethodAnnotated(ManagedOpsFeed.class, Delete.class), "managed feed must answer DELETE");
        assertTrue(hasMethodAnnotated(ManagedOpsFeed.class, Resume.class), "managed feed must declare @Resume");

        // ...the raw side routes them by hand in one onRequest. That asymmetry IS the lesson,
        // so pin that the raw handler really does own the dispatch.
        List<String> declared = Arrays.stream(OpsFeedHandler.class.getDeclaredMethods())
                .map(Method::getName).toList();
        assertTrue(declared.contains("onRequest"),
                "the raw handler must implement onRequest — that is where verb dispatch lives at this layer");
        assertFalse(hasMethodAnnotated(OpsFeedHandler.class, Get.class),
                "verb annotations do not apply below @ManagedService; the switch in onRequest replaces them");
    }

    @Test
    void thePathsAreDistinctSoBothCanRunSideBySide() {
        String raw = OpsFeedHandler.class.getAnnotation(AtmosphereHandlerService.class).path();
        String managed = ManagedOpsFeed.class.getAnnotation(ManagedService.class).path();
        assertNotNull(raw);
        assertNotNull(managed);
        assertNotEquals(raw, managed, "the two feeds must not share a path");
    }
}
