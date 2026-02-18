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
package org.atmosphere.annotation;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.config.service.RoomService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.room.RoomManager;
import org.atmosphere.room.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

import static org.atmosphere.annotation.AnnotationUtil.broadcaster;

/**
 * Processes {@link RoomService} annotations. Sets up a {@link ManagedAtmosphereHandler}
 * for the annotated class and creates the corresponding {@link Room} via the framework's
 * {@link RoomManager}.
 *
 * @since 4.0
 */
@AtmosphereAnnotation(RoomService.class)
public class RoomServiceProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(RoomServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            RoomService a = annotatedClass.getAnnotation(RoomService.class);
            String path = a.path();
            int maxHistory = a.maxHistory();

            List<AtmosphereInterceptor> interceptors = new LinkedList<>();
            AnnotationUtil.defaultManagedServiceInterceptors(framework, interceptors);

            Object instance = framework.newClassInstance(Object.class, annotatedClass);
            AtmosphereHandler handler = framework.newClassInstance(ManagedAtmosphereHandler.class,
                    ManagedAtmosphereHandler.class).configure(framework.getAtmosphereConfig(), instance);

            framework.addAtmosphereHandler(path, handler,
                    broadcaster(framework, org.atmosphere.cpr.DefaultBroadcaster.class, path), interceptors);

            // Create and configure the Room via RoomManager
            RoomManager roomManager = RoomManager.getOrCreate(framework);
            Room room = roomManager.room(path);
            if (maxHistory > 0) {
                room.enableHistory(maxHistory);
            }

            logger.info("@RoomService mapped {} to path '{}' (history={})",
                    annotatedClass.getName(), path, maxHistory);
        } catch (Throwable e) {
            logger.warn("Failed to process @RoomService on " + annotatedClass.getName(), e);
        }
    }
}
